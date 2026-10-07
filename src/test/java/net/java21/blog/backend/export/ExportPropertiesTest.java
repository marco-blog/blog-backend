package net.java21.blog.backend.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 004 T004: blog.export.* 기본값과 잘못된 값(디렉터리 없음 포함)의 기동 실패. */
class ExportPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties(ExportProperties.class)
    static class Config {
    }

    @Test
    void defaults() {
        runner.withPropertyValues("blog.export.dir=/tmp/x").run(context -> assertThat(
                context.getBean(ExportProperties.class)).isEqualTo(new ExportProperties("/tmp/x", Duration.ofDays(7),
                        Duration.ofHours(24), Duration.ofHours(1))));
    }

    @Test
    void invalidValuesFailStartup() {
        runner.run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.export.dir= ").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.export.dir=/tmp/x", "blog.export.retention=0s")
                .run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.export.dir=/tmp/x", "blog.export.min-interval=-1h")
                .run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.export.dir=/tmp/x", "blog.export.stale-running=0s")
                .run(c -> assertThat(c).hasFailed());
    }
}
