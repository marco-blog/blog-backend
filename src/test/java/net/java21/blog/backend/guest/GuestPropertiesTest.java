package net.java21.blog.backend.guest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 004 T004: blog.guest.* 기본값과 잘못된 값의 기동 실패. */
class GuestPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties(GuestProperties.class)
    static class Config {
    }

    @Test
    void defaults() {
        runner.run(context -> assertThat(context.getBean(GuestProperties.class))
                .isEqualTo(new GuestProperties(5, 3, Duration.ofDays(90))));
        assertThat(GuestProperties.defaults().guestbookPerMinute()).isEqualTo(3);
    }

    @Test
    void boundValues() {
        runner.withPropertyValues("blog.guest.comment-per-minute=1000", "blog.guest.guestbook-per-minute=1000",
                "blog.guest.ip-retention=30d").run(context -> assertThat(context.getBean(GuestProperties.class))
                        .isEqualTo(new GuestProperties(1000, 1000, Duration.ofDays(30))));
    }

    @Test
    void invalidValuesFailStartup() {
        runner.withPropertyValues("blog.guest.comment-per-minute=0").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.guest.guestbook-per-minute=-1").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.guest.ip-retention=-1d").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.guest.ip-retention=0s").run(c -> assertThat(c).hasFailed());
    }
}
