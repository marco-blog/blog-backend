package net.java21.blog.backend.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import net.java21.blog.backend.common.job.JobsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 004 T004: blog.posts.*에 더한 보호 글·비밀번호 시도·예약 값과 blog.jobs.export-cleanup-cron. */
class PostsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties({PostsProperties.class, JobsProperties.class})
    static class Config {
    }

    @Test
    void defaults() {
        runner.run(context -> {
            PostsProperties p = context.getBean(PostsProperties.class);
            assertThat(p.unlockTtl()).isEqualTo(Duration.ofMinutes(30));
            assertThat(p.passwordMaxFailures()).isEqualTo(5);
            assertThat(p.passwordLockDuration()).isEqualTo(Duration.ofMinutes(10));
            assertThat(p.scheduleMaxAhead()).isEqualTo(Duration.ofDays(365));
            assertThat(context.getBean(JobsProperties.class).exportCleanupCron()).isEqualTo("0 10 * * * *");
        });
    }

    @Test
    void shortConstructorsKeep004Defaults() {
        PostsProperties p = new PostsProperties(Duration.ofMinutes(30), 10, "v", Duration.ofDays(1));
        assertThat(p.passwordMaxFailures()).isEqualTo(5);
        assertThat(p.unlockTtl()).isEqualTo(PostsProperties.DEFAULT_UNLOCK_TTL);
        assertThat(new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 2, "a", "b").exportCleanupCron())
                .isEqualTo(JobsProperties.DEFAULT_EXPORT_CLEANUP_CRON);
    }

    @Test
    void invalidValuesFailStartup() {
        runner.withPropertyValues("blog.posts.password-max-failures=0").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.posts.unlock-ttl=-1m").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.posts.password-lock-duration=0s").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.posts.schedule-max-ahead=0d").run(c -> assertThat(c).hasFailed());
        assertThatThrownBy(() -> new PostsProperties(Duration.ofMinutes(30), 10, "v", Duration.ofDays(1),
                Duration.ofDays(1), Duration.ofMinutes(1), 1, Duration.ofMinutes(1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
