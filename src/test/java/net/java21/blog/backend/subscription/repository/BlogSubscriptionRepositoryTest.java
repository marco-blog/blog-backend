package net.java21.blog.backend.subscription.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 구독 쓰기와 정리(T018, research D1, 결정 3): {@code INSERT IGNORE}·DELETE 영향 행 수, 원자적 구독자 수(0 밑 금지, {@code updated_at} 불변),
 * 회원 탈퇴 때 구독 전체 삭제 + 블로그별 수 감소(블로그 수와 무관한 쿼리 수), 블로그 id 목록의 구독 행 삭제.
 */
@JpaRepositoryTest
class BlogSubscriptionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private BlogSubscriptionRepository repository;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private JdbcTemplate jdbc;

    private JpaFixtures fx;
    private User reader;
    private Blog blog;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        reader = fx.user("reader");
        blog = fx.blog(fx.user("owner"), "marco");
        fx.flushAndClear();
    }

    @Test
    void insertIgnoreAndDeleteReportAffectedRows() {
        queryCounter.reset();
        assertThat(repository.insertIgnore(reader.getId(), blog.getId(), NOW)).isEqualTo(1);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.insertIgnore(reader.getId(), blog.getId(), NOW)).isZero();
        assertThat(repository.existsByUserIdAndBlogId(reader.getId(), blog.getId())).isTrue();

        queryCounter.reset();
        assertThat(repository.delete(reader.getId(), blog.getId())).isEqualTo(1);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.delete(reader.getId(), blog.getId())).isZero();
        assertThat(repository.existsByUserIdAndBlogId(reader.getId(), blog.getId())).isFalse();
    }

    @Test
    void changeSubscriberCountIsAtomicNeverNegativeAndKeepsUpdatedAt() {
        Timestamp updatedAt = updatedAt(blog);

        queryCounter.reset();
        repository.changeSubscriberCount(blog.getId(), 1);
        assertThat(queryCounter.count()).isEqualTo(1);
        repository.changeSubscriberCount(blog.getId(), 1);
        assertThat(subscriberCount(blog)).isEqualTo(2);
        repository.changeSubscriberCount(blog.getId(), -1);
        repository.changeSubscriberCount(blog.getId(), -1);
        repository.changeSubscriberCount(blog.getId(), -1);
        assertThat(subscriberCount(blog)).isZero();
        assertThat(updatedAt(blog)).isEqualTo(updatedAt);

        queryCounter.reset();
        assertThat(repository.lockSubscriberCount(blog.getId())).contains(0);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.lockSubscriberCount(-1L)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    void withdrawalCleanupRemovesAllSubscriptionsAndDecrementsEachBlogInTwoQueries(int blogs) {
        List<Blog> subscribed = new java.util.ArrayList<>();
        for (int i = 0; i < blogs; i++) {
            Blog b = fx.blog(fx.user("o" + i), "b" + i);
            subscribed.add(b);
        }
        User other = fx.user("other");
        fx.flushAndClear();
        for (Blog b : subscribed) {
            subscribe(reader, b);
            subscribe(other, b);
        }
        subscribe(other, blog);
        Timestamp updatedAt = updatedAt(subscribed.getFirst());

        queryCounter.reset();
        repository.decrementSubscriberCountsOf(reader.getId());
        int deleted = repository.deleteAllByUser(reader.getId());
        assertThat(queryCounter.count()).isEqualTo(2);

        assertThat(deleted).isEqualTo(blogs);
        assertThat(subscribed).allSatisfy(b -> assertThat(subscriberCount(b)).isEqualTo(1));
        assertThat(subscriberCount(blog)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_subscriptions WHERE user_id = ?", Integer.class,
                reader.getId())).isZero();
        assertThat(updatedAt(subscribed.getFirst())).isEqualTo(updatedAt);
    }

    @Test
    void deleteByBlogIdsRemovesOnlyThoseBlogsRows() {
        Blog other = fx.blog(fx.user("o"), "other");
        fx.flushAndClear();
        subscribe(reader, blog);
        subscribe(reader, other);

        queryCounter.reset();
        assertThat(repository.deleteByBlogIds(List.of(blog.getId()))).isEqualTo(1);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT blog_id FROM blog_subscriptions", Long.class))
                .containsExactly(other.getId());
    }

    private void subscribe(User user, Blog b) {
        repository.insertIgnore(user.getId(), b.getId(), NOW);
        repository.changeSubscriberCount(b.getId(), 1);
    }

    private int subscriberCount(Blog b) {
        return jdbc.queryForObject("SELECT subscriber_count FROM blogs WHERE id = ?", Integer.class, b.getId());
    }

    private Timestamp updatedAt(Blog b) {
        return jdbc.queryForObject("SELECT updated_at FROM blogs WHERE id = ?", Timestamp.class, b.getId());
    }
}
