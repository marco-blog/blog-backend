package net.java21.blog.backend.comment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.TrashPurgeRepository;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 실제 스키마(MySQL)에서 확인하는 댓글 동작:
 * <ul>
 *   <li>댓글 행이 실제 {@code comments} 제약(작성자 CHECK, 부모 자기 참조 FK)을 지킨다.</li>
 *   <li>댓글 수 증감 UPDATE가 {@code ON UPDATE CURRENT_TIMESTAMP(6)}에도 글의 {@code updated_at}을 바꾸지 않는다.</li>
 *   <li>휴지통 영구 삭제(T189)가 {@code ON DELETE CASCADE}가 없는 FK(comments 답글 → 댓글, trackbacks, portal_curations,
 *       portal_exclusions)를 먼저 정리해 글을 지운다.</li>
 * </ul>
 */
@MySqlRepositoryTest
@Import({TrashPurgeRepository.class, CommentQueryRepository.class})
class CommentMySqlBehaviourTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private CommentQueryRepository queryRepository;
    @Autowired
    private TrashPurgeRepository trashPurgeRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void commentsFitRealColumnsAndCountKeepsUpdatedAt() {
        User user = persistUser();
        Blog blog = persistBlog(user);
        Post post = persistPost(blog);
        Comment top = new Comment(post, user, null, "한글 댓글 <b>그대로</b> 😀");
        em.persist(top);
        em.persist(new Comment(post, user, top, "답글"));
        em.flush();
        Timestamp updatedAt = jdbc.queryForObject("SELECT updated_at FROM posts WHERE id = ?", Timestamp.class,
                post.getId());

        commentRepository.changeCommentCount(post.getId(), 2);

        assertThat(jdbc.queryForObject("SELECT comment_count FROM posts WHERE id = ?", Integer.class, post.getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM posts WHERE id = ?", Timestamp.class, post.getId()))
                .isEqualTo(updatedAt);
        assertThat(queryRepository.findPostComments(post.getId()))
                .extracting(CommentRow::content).containsExactly("한글 댓글 <b>그대로</b> 😀", "답글");
        assertThat(queryRepository.findBlogComments(blog.getId(), PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(2);
    }

    @Test
    void purgeDeletesCommentsAndRowsReferencingThePostOnMySql() {
        User user = persistUser();
        Blog blog = persistBlog(user);
        Post old = persistPost(blog);
        Post live = persistPost(blog);
        Comment top = new Comment(old, user, null, "댓글");
        em.persist(top);
        em.persist(new Comment(old, user, top, "답글"));
        Comment kept = new Comment(live, user, null, "남는 댓글");
        em.persist(kept);
        em.flush();
        jdbc.update("INSERT INTO trackbacks (post_id, source_url, source_url_hash) VALUES (?, 'https://x.test/a', ?)",
                old.getId(), "a".repeat(64));
        jdbc.update("INSERT INTO trackbacks (post_id, source_post_id, source_url, source_url_hash)"
                + " VALUES (?, ?, 'https://x.test/b', ?)", live.getId(), old.getId(), "b".repeat(64));
        jdbc.update("INSERT INTO portal_curations (created_by, post_id, starts_at, ends_at) VALUES (?, ?, ?, ?)",
                user.getId(), old.getId(), Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(3600)));
        jdbc.update("INSERT INTO portal_exclusions (excluded_by, post_id, reason) VALUES (?, ?, '이유')", user.getId(),
                old.getId());
        jdbc.update("UPDATE posts SET status = 'DELETED', deleted_at = ? WHERE id = ?", Timestamp.from(NOW),
                old.getId());
        em.clear();

        assertThat(trashPurgeRepository.deletePosts(List.of(old.getId()))).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE id = ?", Integer.class, old.getId()))
                .isZero();
        assertThat(jdbc.queryForList("SELECT id FROM comments WHERE post_id IN (?, ?)", Long.class, old.getId(),
                live.getId())).containsExactly(kept.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trackbacks WHERE post_id = ?", Integer.class,
                old.getId())).isZero();
        assertThat(jdbc.queryForList("SELECT source_post_id FROM trackbacks WHERE post_id = ?", Long.class,
                live.getId())).containsExactly((Long) null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portal_curations WHERE post_id = ?", Integer.class,
                old.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM portal_exclusions WHERE post_id = ?", Integer.class,
                old.getId())).isZero();
    }

    private User persistUser() {
        String tag = UUID.randomUUID().toString().replace("-", "");
        User user = new User("c-" + tag + "@example.com", (tag + tag).substring(0, 64), "$2a$hash", "댓글러", null,
                null, "2026-10-06", NOW);
        em.persist(user);
        return user;
    }

    private Blog persistBlog(User user) {
        Blog blog = new Blog(user, "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "블로그");
        em.persist(blog);
        return blog;
    }

    private Post persistPost(Blog blog) {
        Post post = new Post(blog, "글");
        post.publish("글", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, NOW);
        em.persist(post);
        return post;
    }
}
