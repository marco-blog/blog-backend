package net.java21.blog.backend.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 예약 발행 조회·조건부 UPDATE(T078, research B5): 시각이 지난 SCHEDULED만 예약 시각 순으로, 발행은 아직 예약 상태일 때만(이중 발행 없음),
 * {@code published_at}은 실제 발행 시각, {@code scheduled_at}은 지운다. 블로그 첫 발행 시각은 비었을 때만 채운다.
 */
@JpaRepositoryTest
class ScheduledPublishRepositoryTest {

    private static final Instant NOW = JpaFixtures.T0.plusSeconds(3600);

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private QueryCounter queryCounter;

    private ScheduledPublishRepository repository;
    private JpaFixtures fx;
    private Blog blog;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        repository = new ScheduledPublishRepository(queryFactory);
        blog = fx.blog(fx.user("예약자"), "sched");
    }

    @Test
    void findsOnlyDueScheduledPostsInScheduleOrder() {
        Post later = fx.scheduled(blog, "나중", PostVisibility.PUBLIC, NOW.minusSeconds(10));
        Post earlier = fx.scheduled(blog, "먼저", PostVisibility.PROTECTED, NOW.minusSeconds(60));
        Post exactlyNow = fx.scheduled(blog, "지금", PostVisibility.PUBLIC, NOW);
        fx.scheduled(blog, "미래", PostVisibility.PUBLIC, NOW.plusSeconds(1));
        fx.published(blog, "발행", null, 1);
        fx.flushAndClear();

        queryCounter.reset();
        assertThat(repository.findDueIds(NOW, 10)).containsExactly(earlier.getId(), later.getId(), exactlyNow.getId());
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.findDueIds(NOW, 2)).containsExactly(earlier.getId(), later.getId());
    }

    @Test
    void publishIfDueSetsPublishedAtAndClearsScheduleOnce() {
        Post due = fx.scheduled(blog, "예약", PostVisibility.PUBLIC, NOW.minusSeconds(30));
        fx.flushAndClear();

        assertThat(repository.publishIfDue(due.getId(), NOW)).isEqualTo(1);
        assertThat(repository.publishIfDue(due.getId(), NOW.plusSeconds(30))).as("두 번째 실행은 0행").isZero();
        fx.flushAndClear();

        Post reloaded = em.find(Post.class, due.getId());
        assertThat(reloaded.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(reloaded.getPublishedAt()).isEqualTo(NOW);
        assertThat(reloaded.getScheduledAt()).isNull();
    }

    @Test
    void publishIfDueSkipsFutureUnscheduledOrTrashedPosts() {
        Post future = fx.scheduled(blog, "미래", PostVisibility.PUBLIC, NOW.plusSeconds(60));
        Post unscheduled = fx.scheduled(blog, "취소", PostVisibility.PUBLIC, NOW.minusSeconds(60));
        unscheduled.unschedule();
        Post trashed = fx.scheduled(blog, "휴지통", PostVisibility.PUBLIC, NOW.minusSeconds(60));
        trashed.moveToTrash(NOW.minusSeconds(5));
        fx.flushAndClear();

        assertThat(repository.publishIfDue(future.getId(), NOW)).isZero();
        assertThat(repository.publishIfDue(unscheduled.getId(), NOW)).isZero();
        assertThat(repository.publishIfDue(trashed.getId(), NOW)).isZero();
        assertThat(repository.findDueIds(NOW, 10)).isEmpty();
        fx.flushAndClear();
        assertThat(em.find(Post.class, unscheduled.getId()).getStatus()).isEqualTo(PostStatus.DRAFT);
        assertThat(em.find(Post.class, trashed.getId()).getStatus()).isEqualTo(PostStatus.DELETED);
    }

    @Test
    void markBlogFirstPublishedOnlyWhenEmpty() {
        Post due = fx.scheduled(blog, "예약", PostVisibility.PUBLIC, NOW.minusSeconds(30));
        fx.flushAndClear();

        assertThat(repository.markBlogFirstPublished(due.getId(), NOW)).isEqualTo(1);
        assertThat(repository.markBlogFirstPublished(due.getId(), NOW.plusSeconds(60))).isZero();
        fx.flushAndClear();
        assertThat(em.find(Blog.class, blog.getId()).getFirstPublishedAt()).isEqualTo(NOW);
    }
}
