package net.java21.blog.backend.blog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.FeedContentMode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 002 컬럼 매핑(T005): 블로그 구독자 수·피드 설정, 글 좋아요 수. 카운터는 읽기 전용이라 엔티티 저장으로 바뀌지 않는다
 * (구독·좋아요의 원자적 UPDATE만 바꾼다, research D1).
 */
@JpaRepositoryTest
class DiscoveryColumnsMappingTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private JpaFixtures fx;
    private User owner;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        owner = fx.user("marco");
    }

    @Test
    void newBlogHasDiscoveryDefaults() {
        Blog blog = fx.blog(owner, "marco");
        fx.flushAndClear();

        Blog found = em.find(Blog.class, blog.getId());
        assertThat(found.getSubscriberCount()).isZero();
        assertThat(found.getFeedItemCount()).isEqualTo(20);
        assertThat(found.getFeedContentMode()).isEqualTo(FeedContentMode.FULL);
    }

    @Test
    void feedSettingsAreSavedAndReadBack() {
        Blog blog = fx.blog(owner, "marco");
        fx.flushAndClear();

        em.find(Blog.class, blog.getId()).changeFeedSettings(30, FeedContentMode.SUMMARY);
        fx.flushAndClear();

        Blog found = em.find(Blog.class, blog.getId());
        assertThat(found.getFeedItemCount()).isEqualTo(30);
        assertThat(found.getFeedContentMode()).isEqualTo(FeedContentMode.SUMMARY);
        assertThat(jdbc.queryForObject("SELECT feed_content_mode FROM blogs WHERE id = ?", String.class, blog.getId()))
                .isEqualTo("SUMMARY");
    }

    @Test
    void feedSettingsRejectValuesOutsideTheAllowedSet() {
        Blog blog = fx.blog(owner, "marco");

        assertThatThrownBy(() -> blog.changeFeedSettings(15, FeedContentMode.FULL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> blog.changeFeedSettings(10, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(blog.getFeedItemCount()).isEqualTo(20);
    }

    @Test
    void newPostHasZeroLikes() {
        Post post = fx.published(fx.blog(owner, "marco"), "글", null, 0);
        fx.flushAndClear();

        assertThat(em.find(Post.class, post.getId()).getLikeCount()).isZero();
    }

    @Test
    void countersAreReadOnlyForEntitySaves() {
        Blog blog = fx.blog(owner, "marco");
        Post post = fx.published(blog, "글", null, 0);
        fx.flushAndClear();
        jdbc.update("UPDATE blogs SET subscriber_count = 3 WHERE id = ?", blog.getId());
        jdbc.update("UPDATE posts SET like_count = 5 WHERE id = ?", post.getId());

        Blog loadedBlog = em.find(Blog.class, blog.getId());
        Post loadedPost = em.find(Post.class, post.getId());
        assertThat(loadedBlog.getSubscriberCount()).isEqualTo(3);
        assertThat(loadedPost.getLikeCount()).isEqualTo(5);
        ReflectionTestUtils.setField(loadedBlog, "subscriberCount", 99);
        ReflectionTestUtils.setField(loadedPost, "likeCount", 99);
        loadedBlog.changeTitle("새 제목");
        loadedPost.syncDraftTitle("ignored");
        ReflectionTestUtils.setField(loadedPost, "title", "새 글 제목");
        fx.flushAndClear();

        assertThat(jdbc.queryForObject("SELECT subscriber_count FROM blogs WHERE id = ?", Integer.class, blog.getId()))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT like_count FROM posts WHERE id = ?", Integer.class, post.getId()))
                .isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT title FROM blogs WHERE id = ?", String.class, blog.getId()))
                .isEqualTo("새 제목");
    }
}
