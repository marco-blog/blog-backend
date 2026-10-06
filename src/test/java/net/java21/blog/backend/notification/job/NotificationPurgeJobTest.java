package net.java21.blog.backend.notification.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.notification.NotificationsProperties;
import net.java21.blog.backend.notification.domain.Notification;
import net.java21.blog.backend.notification.domain.NotificationTargetType;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.user.domain.User;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 오래된 알림 정리(T046, 002 data-model notifications): 90일 지난 알림만 건수 단위로 지우고 처리 건수를 로그에 남긴다. */
@JpaRepositoryTest
@ExtendWith(OutputCaptureExtension.class)
@Import(NotificationPurgeJobTest.Config.class)
class NotificationPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:15:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    @Import(NotificationQueryRepository.class)
    static class Config {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        /** 배치 크기 2: 여러 번에 나눠 처리하는지 확인한다. */
        @Bean
        NotificationPurgeJob notificationPurgeJob(NotificationQueryRepository repository,
                TransactionTemplate transactionTemplate, MutableClock clock) {
            return new NotificationPurgeJob(repository, transactionTemplate,
                    new NotificationsProperties(Duration.ofDays(90), Duration.ofHours(24)),
                    new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 2), clock);
        }
    }

    @Autowired
    private NotificationPurgeJob job;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = new JpaFixtures(em).user("owner");
    }

    private void notificationAt(Instant createdAt) {
        clock.set(createdAt);
        em.persist(new Notification(owner, null, null, NotificationType.NEW_SUBSCRIBER, NotificationTargetType.BLOG,
                1L, Map.of("blogTitle", "블로그")));
        em.flush();
    }

    @Test
    void deletesOnlyNotificationsOlderThanRetentionInBatches(CapturedOutput output) {
        Instant cutoff = NOW.minus(Duration.ofDays(90));
        notificationAt(cutoff.minus(Duration.ofDays(10)));
        notificationAt(cutoff.minus(Duration.ofDays(1)));
        notificationAt(cutoff.minusSeconds(1));
        notificationAt(cutoff);
        notificationAt(NOW.minus(Duration.ofDays(1)));
        clock.set(NOW);

        assertThat(job.purge()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Long.class)).isEqualTo(2);
        assertThat(output).contains("Notification purge finished: notifications=3");

        assertThat(job.purge()).isZero();
    }

    @Test
    void scheduledRunUsesConfiguredCron() throws NoSuchMethodException {
        Scheduled scheduled = NotificationPurgeJob.class.getMethod("run").getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${blog.jobs.notification-purge-cron:0 15 4 * * *}");
        job.run();
        assertThat(new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 500).notificationPurgeCron())
                .isEqualTo("0 15 4 * * *");
    }
}
