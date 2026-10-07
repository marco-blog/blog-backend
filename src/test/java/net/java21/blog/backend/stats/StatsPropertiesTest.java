package net.java21.blog.backend.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 004 T004: blog.stats.* 기본값과 잘못된 값의 기동 실패. */
class StatsPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties(StatsProperties.class)
    static class Config {
    }

    @Test
    void defaults() {
        runner.run(context -> {
            StatsProperties p = context.getBean(StatsProperties.class);
            assertThat(p.zone()).isEqualTo(ZoneId.of("Asia/Seoul"));
            assertThat(p.visitDedupMaxSize()).isEqualTo(200_000);
            assertThat(p.botPattern().matcher("Mozilla/5.0 (compatible; Googlebot/2.1)").find()).isTrue();
            assertThat(p.botPattern().matcher("facebookexternalhit/1.1").find()).isTrue();
            assertThat(p.botPattern().matcher("Mozilla/5.0 HeadlessChrome/120").find()).isFalse();
        });
        assertThat(StatsProperties.defaults().timeZone()).isEqualTo("Asia/Seoul");
    }

    @Test
    void boundValues() {
        runner.withPropertyValues("blog.stats.time-zone=UTC", "blog.stats.visit-dedup-max-size=10",
                "blog.stats.bot-user-agent-pattern=(?i)crawler").run(context -> {
                    StatsProperties p = context.getBean(StatsProperties.class);
                    assertThat(p.zone()).isEqualTo(ZoneId.of("UTC"));
                    assertThat(p.visitDedupMaxSize()).isEqualTo(10);
                    assertThat(p.botPattern().pattern()).isEqualTo("(?i)crawler");
                });
    }

    @Test
    void invalidValuesFailStartup() {
        runner.withPropertyValues("blog.stats.time-zone=Mars/Base").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.stats.visit-dedup-max-size=0").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.stats.bot-user-agent-pattern=(unclosed").run(c -> assertThat(c).hasFailed());
        assertThatThrownBy(() -> new StatsProperties(" ", 1, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatsProperties("UTC", 1, "")).isInstanceOf(IllegalArgumentException.class);
    }
}
