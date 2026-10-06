package net.java21.blog.backend.comment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 댓글 조회(T186): 글 댓글 트리(작성순, 답글 포함)와 작성자를 댓글 수와 무관한 쿼리 수로, 블로그 관리 목록(최신순 페이지, postId·postTitle),
 * 최근 7일 새 댓글 수, 댓글 수 증감, 답글 존재 확인.
 */
@JpaRepositoryTest
@Import({CommentQueryRepository.class, CommentRepositoryTest.Config.class})
class CommentRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private EntityManager em;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private CommentQueryRepository queryRepository;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private MutableClock clock;
    @Autowired
    private JdbcTemplate jdbc;

    private User owner;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        owner = user("owner", "주인");
        blog = new Blog(owner, "marco", "마르코의 블로그");
        em.persist(blog);
        post = publishedPost(blog, "첫 글");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 12})
    void postCommentTreeInOneQueryRegardlessOfCount(int threads) {
        for (int i = 0; i < threads; i++) {
            User writer = user("w" + i, "작성자" + i);
            Comment top = comment(post, writer, null, "댓글 " + i);
            comment(post, owner, top, "답글 " + i);
            comment(post, user("r" + i, "답글러" + i), top, "또 답글 " + i);
        }
        em.flush();
        em.clear();
        queryCounter.reset();

        List<CommentRow> rows = queryRepository.findPostComments(post.getId());

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(rows).hasSize(threads * 3);
        assertThat(rows.getFirst().nickname()).isEqualTo("작성자0");
        assertThat(rows.getFirst().parentId()).isNull();
        assertThat(rows.get(1).parentId()).isEqualTo(rows.getFirst().id());
        assertThat(rows.get(1).nickname()).isEqualTo("주인");
        assertThat(rows.get(1).userId()).isEqualTo(owner.getId());
    }

    @Test
    void postCommentsInWritingOrderIncludingDeletedPlaceholders() {
        Comment first = comment(post, owner, null, "먼저");
        clock.advance(Duration.ofMinutes(1));
        Comment second = comment(post, owner, null, "나중");
        first.markDeleted();
        comment(publishedPost(blog, "다른 글"), owner, null, "다른 글의 댓글");
        em.flush();
        em.clear();

        assertThat(queryRepository.findPostComments(post.getId()))
                .extracting(CommentRow::id, CommentRow::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(first.getId(), CommentStatus.DELETED),
                        org.assertj.core.groups.Tuple.tuple(second.getId(), CommentStatus.ACTIVE));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 25})
    void blogCommentsNewestFirstPagedWithPostTitleInTwoQueries(int count) {
        Post other = publishedPost(blog, "둘째 글");
        Post trashed = publishedPost(blog, "버린 글");
        for (int i = 0; i < count; i++) {
            comment(i % 2 == 0 ? post : other, user("u" + i, "회원" + i), null, "댓글 " + i);
            clock.advance(Duration.ofSeconds(1));
        }
        Comment gone = comment(post, owner, null, "삭제 자리");
        gone.markDeleted();
        comment(trashed, owner, null, "휴지통 글 댓글");
        trashed.moveToTrash(NOW);
        Blog otherBlog = new Blog(owner, "other", "다른 블로그");
        em.persist(otherBlog);
        comment(publishedPost(otherBlog, "남의 글"), owner, null, "다른 블로그 댓글");
        em.flush();
        em.clear();
        queryCounter.reset();

        Page<BlogCommentRow> page = queryRepository.findBlogComments(blog.getId(), PageRequest.of(0, 20));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(count);
        assertThat(page.getContent()).hasSize(Math.min(count, 20));
        BlogCommentRow newest = page.getContent().getFirst();
        assertThat(newest.content()).isEqualTo("댓글 " + (count - 1));
        assertThat(newest.nickname()).isEqualTo("회원" + (count - 1));
        assertThat(newest.postId()).isEqualTo((count - 1) % 2 == 0 ? post.getId() : other.getId());
        assertThat(newest.postTitle()).isEqualTo((count - 1) % 2 == 0 ? "첫 글" : "둘째 글");
    }

    @Test
    void recentBlogCommentsAndCountSince() {
        comment(post, owner, null, "오래된 댓글");
        clock.advance(Duration.ofDays(8));
        Comment recent1 = comment(post, owner, null, "최근 1");
        clock.advance(Duration.ofMinutes(1));
        Comment recent2 = comment(post, owner, recent1, "최근 2");
        em.flush();
        em.clear();
        queryCounter.reset();

        List<BlogCommentRow> recent = queryRepository.findRecentBlogComments(blog.getId(), 5);
        long since = queryRepository.countBlogCommentsSince(blog.getId(), clock.instant().minus(Duration.ofDays(7)));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(recent).extracting(BlogCommentRow::id).containsExactly(recent2.getId(), recent1.getId(),
                recent.get(2).id());
        assertThat(since).isEqualTo(2);
        assertThat(queryRepository.findRecentBlogComments(blog.getId(), 1)).hasSize(1);
    }

    @Test
    void commentCountChangesAtomicallyWithoutTouchingUpdatedAtAndNeverBelowZero() {
        em.flush();
        Instant updatedAt = post.getUpdatedAt();
        clock.advance(Duration.ofHours(1));

        commentRepository.changeCommentCount(post.getId(), 1);
        commentRepository.changeCommentCount(post.getId(), 1);
        commentRepository.changeCommentCount(post.getId(), -1);

        Post reloaded = em.find(Post.class, post.getId());
        assertThat(reloaded.getCommentCount()).isEqualTo(1);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
        commentRepository.changeCommentCount(post.getId(), -5);
        assertThat(jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?", Integer.class, post.getId()))
                .isZero();
    }

    @Test
    void findWithPostAndOwnerAndReplyChecks() {
        Comment top = comment(post, owner, null, "댓글");
        Comment reply = comment(post, owner, top, "답글");
        em.flush();
        em.clear();
        queryCounter.reset();

        Comment found = commentRepository.findWithPostAndOwner(reply.getId()).orElseThrow();
        assertThat(found.getPost().isOwnedBy(owner.getId())).isTrue();
        assertThat(found.isWrittenBy(owner.getId())).isTrue();
        assertThat(queryCounter.count()).isEqualTo(1);

        assertThat(commentRepository.existsByParentId(top.getId())).isTrue();
        assertThat(commentRepository.existsByParentId(reply.getId())).isFalse();
        assertThat(commentRepository.existsByParentIdAndIdNot(top.getId(), reply.getId())).isFalse();
        assertThat(commentRepository.findWithPostAndOwner(-1L)).isEmpty();
    }

    private User user(String key, String nickname) {
        User user = new User(key + "@example.com", (key + "-".repeat(64)).substring(0, 64), "$2a$hash", nickname,
                null, null, "2026-10-06", NOW);
        em.persist(user);
        return user;
    }

    private Post publishedPost(Blog blog, String title) {
        Post post = new Post(blog, title);
        post.publish(title, "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, NOW);
        em.persist(post);
        return post;
    }

    private Comment comment(Post post, User user, Comment parent, String content) {
        Comment comment = new Comment(post, user, parent, content);
        em.persist(comment);
        return comment;
    }
}
