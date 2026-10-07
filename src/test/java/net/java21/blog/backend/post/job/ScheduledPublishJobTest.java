package net.java21.blog.backend.post.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.ScheduledPublishRepository;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import net.java21.blog.backend.trackback.service.TrackbackSendRequested;
import net.java21.blog.backend.trackback.service.TrackbackSendService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예약 발행 작업(T079, FR-064, SC-010): 시각이 지난 예약 글을 묶음(여기서는 2개)으로 모두 발행하고, 미래·취소한 글은 두며,
 * 발행한 글의 블로그 첫 발행 시각을 채운다. 발행한 것이 있을 때만 로그를 남긴다. 다시 돌려도 이중 발행하지 않는다.
 */
@JpaRepositoryTest
@ExtendWith(OutputCaptureExtension.class)
@Import(ScheduledPublishJobTest.Config.class)
@RecordApplicationEvents
class ScheduledPublishJobTest {

    private static final Instant NOW = Instant.parse("2026-10-06T03:30:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    @Import({ScheduledPublishRepository.class, TrackbackSendService.class})
    static class Config {

        @Bean
        TrackbackProperties trackbackProperties() {
            return TrackbackProperties.defaults();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        ScheduledPublishJob scheduledPublishJob(ScheduledPublishRepository repository,
                TransactionTemplate transactionTemplate, TrackbackSendService trackbacks, MutableClock clock) {
            return new ScheduledPublishJob(repository, transactionTemplate,
                    new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 2), trackbacks, clock);
        }
    }

    @Autowired
    private ScheduledPublishJob job;
    @Autowired
    private EntityManager em;
    @Autowired
    private MutableClock clock;
    @Autowired
    private ApplicationEvents events;

    private JpaFixtures fx;
    private Blog blog;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        fx = new JpaFixtures(em);
        blog = fx.blog(fx.user("예약 작업"), "schedjob");
    }

    @Test
    void publishesAllDuePostsAcrossBatchesAndLogs(CapturedOutput output) {
        List<Post> due = List.of(
                fx.scheduled(blog, "1", PostVisibility.PUBLIC, NOW.minusSeconds(90)),
                fx.scheduled(blog, "2", PostVisibility.PROTECTED, NOW.minusSeconds(60)),
                fx.scheduled(blog, "3", PostVisibility.PRIVATE, NOW.minusSeconds(30)),
                fx.scheduled(blog, "4", PostVisibility.PUBLIC, NOW.minusSeconds(1)),
                fx.scheduled(blog, "5", PostVisibility.PUBLIC, NOW));
        Post future = fx.scheduled(blog, "미래", PostVisibility.PUBLIC, NOW.plusSeconds(30));
        Post cancelled = fx.scheduled(blog, "취소", PostVisibility.PUBLIC, NOW.minusSeconds(30));
        cancelled.unschedule();
        fx.flushAndClear();

        assertThat(job.publishDue()).isEqualTo(5);
        fx.flushAndClear();

        for (Post p : due) {
            Post reloaded = em.find(Post.class, p.getId());
            assertThat(reloaded.getStatus()).isEqualTo(PostStatus.PUBLISHED);
            assertThat(reloaded.getPublishedAt()).isEqualTo(NOW);
            assertThat(reloaded.getScheduledAt()).isNull();
        }
        assertThat(em.find(Post.class, future.getId()).getStatus()).isEqualTo(PostStatus.SCHEDULED);
        assertThat(em.find(Post.class, cancelled.getId()).getStatus()).isEqualTo(PostStatus.DRAFT);
        assertThat(em.find(Blog.class, blog.getId()).getFirstPublishedAt()).isEqualTo(NOW);
        assertThat(output).contains("Scheduled publish finished: published=5");

        assertThat(job.publishDue()).as("다시 돌려도 이중 발행 없음").isZero();
    }

    @Test
    void nextRunPublishesWhenTheTimeComesWithinOneMinute(CapturedOutput output) {
        Post post = fx.scheduled(blog, "곧", PostVisibility.PUBLIC, NOW.plusSeconds(20));
        fx.flushAndClear();

        job.run();
        assertThat(output).doesNotContain("Scheduled publish finished");
        fx.flushAndClear();
        assertThat(em.find(Post.class, post.getId()).getStatus()).isEqualTo(PostStatus.SCHEDULED);

        clock.advance(Duration.ofSeconds(30));
        job.run();
        fx.flushAndClear();
        Post published = em.find(Post.class, post.getId());
        assertThat(published.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(Duration.between(NOW.plusSeconds(20), published.getPublishedAt())).isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void publishedPostsSendTheirPendingTrackbacksAfterCommitButFuturePostsKeepThem() {
        Post due = fx.scheduled(blog, "보낼 글", PostVisibility.PUBLIC, NOW.minusSeconds(10));
        Post future = fx.scheduled(blog, "나중 글", PostVisibility.PUBLIC, NOW.plusSeconds(600));
        TrackbackPingLog dueLog = new TrackbackPingLog(due, "https://other.example/tb/1");
        TrackbackPingLog futureLog = new TrackbackPingLog(future, "https://other.example/tb/2");
        em.persist(dueLog);
        em.persist(futureLog);
        fx.flushAndClear();

        assertThat(job.publishDue()).isEqualTo(1);

        assertThat(events.stream(TrackbackSendRequested.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.postId()).isEqualTo(due.getId());
                    assertThat(e.logIds()).containsExactly(dueLog.getId());
                });
    }
}
