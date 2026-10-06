package net.java21.blog.backend.like.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 좋아요 쓰기(T014, research D1): {@code INSERT IGNORE}·DELETE의 영향 행 수, 원자적 카운터(0 밑 금지, {@code updated_at} 불변),
 * 잠금 읽기, 누름 여부. 각 호출은 쿼리 1회.
 */
@JpaRepositoryTest
class PostLikeRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private PostLikeRepository repository;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private JdbcTemplate jdbc;

    private User reader;
    private Post post;

    @BeforeEach
    void setUp() {
        JpaFixtures fx = new JpaFixtures(em);
        User owner = fx.user("owner");
        reader = fx.user("reader");
        Blog blog = fx.blog(owner, "marco");
        post = fx.published(blog, "글", null, 0);
        fx.flushAndClear();
    }

    @Test
    void insertIgnoreInsertsOnceInOneQuery() {
        queryCounter.reset();
        assertThat(repository.insertIgnore(reader.getId(), post.getId(), NOW)).isEqualTo(1);
        assertThat(queryCounter.count()).isEqualTo(1);

        queryCounter.reset();
        assertThat(repository.insertIgnore(reader.getId(), post.getId(), NOW.plusSeconds(5))).isZero();
        assertThat(queryCounter.count()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM post_likes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_at FROM post_likes", Timestamp.class).toInstant())
                .isEqualTo(NOW);
    }

    @Test
    void deleteReportsWhetherARowWasRemoved() {
        repository.insertIgnore(reader.getId(), post.getId(), NOW);

        queryCounter.reset();
        assertThat(repository.delete(reader.getId(), post.getId())).isEqualTo(1);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.delete(reader.getId(), post.getId())).isZero();
    }

    @Test
    void changeLikeCountIsAtomicNeverNegativeAndKeepsUpdatedAt() {
        Timestamp updatedAt = updatedAt();

        queryCounter.reset();
        assertThat(repository.changeLikeCount(post.getId(), 1)).isEqualTo(1);
        assertThat(queryCounter.count()).isEqualTo(1);
        repository.changeLikeCount(post.getId(), 1);
        assertThat(likeCount()).isEqualTo(2);

        repository.changeLikeCount(post.getId(), -1);
        repository.changeLikeCount(post.getId(), -1);
        repository.changeLikeCount(post.getId(), -1);
        assertThat(likeCount()).isZero();
        assertThat(updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void lockLikeCountReadsTheCurrentCountOrNothing() {
        repository.changeLikeCount(post.getId(), 1);

        queryCounter.reset();
        assertThat(repository.lockLikeCount(post.getId())).contains(1);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.lockLikeCount(-1L)).isEmpty();
    }

    @Test
    void existsByUserIdAndPostIdInOneQuery() {
        repository.insertIgnore(reader.getId(), post.getId(), NOW);

        queryCounter.reset();
        assertThat(repository.existsByUserIdAndPostId(reader.getId(), post.getId())).isTrue();
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.existsByUserIdAndPostId(reader.getId() + 100, post.getId())).isFalse();
    }

    private int likeCount() {
        return jdbc.queryForObject("SELECT like_count FROM posts WHERE id = ?", Integer.class, post.getId());
    }

    private Timestamp updatedAt() {
        return jdbc.queryForObject("SELECT updated_at FROM posts WHERE id = ?", Timestamp.class, post.getId());
    }
}
