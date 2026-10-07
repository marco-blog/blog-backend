package net.java21.blog.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 006 T005: blog.admin.* 기본값(대시보드 캐시 5분, 작업 기록 보관 365일)과 잘못된 값의 기동 실패. */
class AdminPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties(AdminProperties.class)
    static class Config {
    }

    @Test
    void defaults() {
        runner.run(context -> {
            AdminProperties properties = context.getBean(AdminProperties.class);
            assertThat(properties.dashboardCacheTtl()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.auditRetention()).isEqualTo(Duration.ofDays(365));
            assertThat(properties.hasBootstrapEmail()).isFalse();
        });
        assertThat(new AdminProperties("boss@example.com"))
                .isEqualTo(new AdminProperties("boss@example.com", Duration.ofMinutes(5), Duration.ofDays(365)));
    }

    @Test
    void zeroCacheTtlTurnsCacheOff() {
        runner.withPropertyValues("blog.admin.dashboard-cache-ttl=0s", "blog.admin.audit-retention=30d")
                .run(context -> {
                    AdminProperties properties = context.getBean(AdminProperties.class);
                    assertThat(properties.dashboardCacheTtl()).isZero();
                    assertThat(properties.auditRetention()).isEqualTo(Duration.ofDays(30));
                });
    }

    @Test
    void invalidValuesFailStartup() {
        runner.withPropertyValues("blog.admin.dashboard-cache-ttl=-1s").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.admin.audit-retention=29d").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.admin.audit-retention=0s").run(c -> assertThat(c).hasFailed());
    }
}
