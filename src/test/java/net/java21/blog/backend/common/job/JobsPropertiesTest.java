package net.java21.blog.backend.common.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 006 T005: blog.jobs.audit-purge-cron 기본값(매일 05:15)과 기존 보조 생성자, 잘못된 값의 기동 실패. */
class JobsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties(JobsProperties.class)
    static class Config {
    }

    @Test
    void auditPurgeCronDefaults() {
        runner.run(context -> assertThat(context.getBean(JobsProperties.class).auditPurgeCron())
                .isEqualTo("0 15 5 * * *"));
        runner.withPropertyValues("blog.jobs.audit-purge-cron=0 0 6 * * *")
                .run(context -> assertThat(context.getBean(JobsProperties.class).auditPurgeCron())
                        .isEqualTo("0 0 6 * * *"));
        assertThat(new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 2).auditPurgeCron())
                .isEqualTo(JobsProperties.DEFAULT_AUDIT_PURGE_CRON);
        assertThat(new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 2, "a", "b", "c").auditPurgeCron())
                .isEqualTo(JobsProperties.DEFAULT_AUDIT_PURGE_CRON);
    }

    @Test
    void invalidValuesFailStartup() {
        runner.withPropertyValues("blog.jobs.purge-batch-size=0").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.jobs.trash-retention=0s").run(c -> assertThat(c).hasFailed());
    }
}
