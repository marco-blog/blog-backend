package net.java21.blog.backend.post.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostDailyStatsRepository;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 오래된 일별 통계 정리(003 T031, research P4): 보관 기간(90일)이 지난 행만 배치 단위로 지우고 건수를 로그에 남긴다. */
@JpaRepositoryTest
@ExtendWith(OutputCaptureExtension.class)
@Import(PostStatsPurgeJobTest.Config.class)
class PostStatsPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:45:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-06");

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }

        /** 배치 크기 2: 여러 번에 나눠 지우는지 확인한다. */
        @Bean
        PostStatsPurgeJob postStatsPurgeJob(PostDailyStatsRepository repository,
                PlatformTransactionManager transactionManager, MutableClock clock) {
            return new PostStatsPurgeJob(repository, new TransactionTemplate(transactionManager),
                    new PostsProperties(Duration.ofMinutes(30), 10, "visitor_id", Duration.ofDays(365),
                            Duration.ofDays(90)),
                    new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 2), clock);
        }
    }

    @Autowired
    private PostStatsPurgeJob job;
    @Autowired
    private PostDailyStatsRepository repository;
    @Autowired
    private EntityManager em;

    @Test
    void purgesOnlyRowsOlderThanRetentionInBatchesAndLogsTheCount(CapturedOutput output) {
        JpaFixtures fx = new JpaFixtures(em);
        Post post = fx.published(fx.blog(fx.user("marco"), "marco"), "글", null, 1);
        Post other = fx.published(fx.blog(fx.user("other"), "other"), "글", null, 1);
        em.flush();
        LocalDate cutoff = TODAY.minusDays(90);
        for (LocalDate date : new LocalDate[] {cutoff.minusDays(30), cutoff.minusDays(2), cutoff.minusDays(1), cutoff,
                TODAY}) {
            repository.upsertView(post.getId(), date, NOW);
        }
        repository.upsertView(other.getId(), cutoff.minusDays(1), NOW);
        repository.upsertView(other.getId(), cutoff.plusDays(1), NOW);

        assertThat(job.purge()).isEqualTo(4);
        em.clear();

        assertThat(repository.findAll()).extracting(stat -> stat.getId().statDate())
                .containsExactlyInAnyOrder(cutoff, TODAY, cutoff.plusDays(1));
        assertThat(output).contains("Post stats purge finished: rows=4, before=" + cutoff);
        assertThat(job.purge()).isZero();
    }

    @Test
    void statsRetentionMustBePositive() {
        assertThatThrownBy(() -> new PostsProperties(Duration.ofMinutes(30), 10, "v", Duration.ofDays(1),
                Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
