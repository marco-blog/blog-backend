package net.java21.blog.backend.sidebar.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.sidebar.domain.BlogSidebarItem;
import net.java21.blog.backend.sidebar.domain.SidebarItemType;
import net.java21.blog.backend.sidebar.dto.SidebarPostResponse;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** 사이드바 조회(T052, FR-060): 최근 글·인기 글·최근 댓글, 설정 읽기, 각 쿼리 1회. */
@JpaRepositoryTest
@Import(SidebarQueryRepository.class)
class SidebarQueryRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private SidebarQueryRepository repository;
    @Autowired
    private BlogSidebarItemRepository itemRepository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private Blog blog;
    private User owner;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        owner = fx.user("marco");
        blog = fx.blog(owner, "marco");
    }

    @Test
    void recentAndPopularPostsAreListableOnly() {
        Post a = views(fx.published(blog, "A", null, 1), 10);
        Post b = views(fx.published(blog, "B", null, 2), 30);
        Post c = views(fx.published(blog, "C", null, 3), 10);
        views(fx.post(blog, "비공개", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 4), 99);
        fx.post(blog, "임시", null, PostStatus.DRAFT, PostVisibility.PUBLIC, 5);
        fx.published(fx.blog(fx.user("polo"), "polo"), "남의 글", null, 6);
        fx.flushAndClear();

        queryCounter.reset();
        List<SidebarPostResponse> recent = repository.findRecentPosts(blog.getId(), 5);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(recent).extracting(SidebarPostResponse::id).containsExactly(c.getId(), b.getId(), a.getId());
        assertThat(recent.getFirst().publishedAt()).isEqualTo(JpaFixtures.T0.plusSeconds(180));

        queryCounter.reset();
        assertThat(repository.findPopularPosts(blog.getId(), 2)).extracting(SidebarPostResponse::id)
                .containsExactly(b.getId(), c.getId());
        assertThat(queryCounter.count()).isEqualTo(1);
    }

    @Test
    void recentCommentsAreNonSecretActiveOnBodyVisiblePosts() {
        Post visible = fx.published(blog, "공개 글", null, 1);
        Post hidden = fx.post(blog, "비공개 글", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 2);
        User reader = fx.user("reader");
        Comment member = comment(new Comment(visible, reader, null, "회원 댓글\n둘째 줄"));
        Comment guest = comment(Comment.byGuest(visible, null, "손님", "$2a$x", null, "비회원 댓글", false));
        comment(Comment.byGuest(visible, null, "비밀손님", "$2a$x", null, "비밀 댓글", true));
        comment(new Comment(hidden, reader, null, "비공개 글 댓글"));
        Comment removed = comment(new Comment(visible, reader, null, "지운 댓글"));
        removed.markDeleted();
        fx.flushAndClear();

        queryCounter.reset();
        List<SidebarCommentRow> rows = repository.findRecentComments(blog.getId(), 5);

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(rows).extracting(SidebarCommentRow::id).containsExactly(guest.getId(), member.getId());
        assertThat(rows.get(0).guestName()).isEqualTo("손님");
        assertThat(rows.get(0).nickname()).isNull();
        assertThat(rows.get(1).nickname()).isEqualTo("reader");
        assertThat(rows.get(1).postTitle()).isEqualTo("공개 글");
        assertThat(rows.get(1).postId()).isEqualTo(visible.getId());
    }

    @Test
    void settingsAreReadInOrderAndReplaced() {
        em.persist(new BlogSidebarItem(blog, SidebarItemType.TAGS, true, 1));
        em.persist(new BlogSidebarItem(blog, SidebarItemType.PROFILE, false, 0));
        fx.flushAndClear();

        queryCounter.reset();
        assertThat(itemRepository.findByBlogIdOrderBySortOrder(blog.getId())).extracting(BlogSidebarItem::getType)
                .containsExactly(SidebarItemType.PROFILE, SidebarItemType.TAGS);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(itemRepository.deleteByBlogId(blog.getId())).isEqualTo(2);
        assertThat(itemRepository.findByBlogIdOrderBySortOrder(blog.getId())).isEmpty();
    }

    private Comment comment(Comment comment) {
        em.persist(comment);
        em.flush();
        return comment;
    }

    private static Post views(Post post, int count) {
        return TestEntities.with(post, "viewCount", count);
    }
}
