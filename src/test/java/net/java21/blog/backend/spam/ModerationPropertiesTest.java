package net.java21.blog.backend.spam;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import net.java21.blog.backend.report.ReportsProperties;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.user.PrivacyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 005 T005: blog.ratelimit·spam·reports·trackback·privacy 기본값과 잘못된 값의 기동 실패. */
class ModerationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties({RateLimitProperties.class, SpamProperties.class, ReportsProperties.class,
            TrackbackProperties.class, PrivacyProperties.class})
    static class Config {
    }

    @Test
    void defaults() {
        runner.run(context -> {
            assertThat(context.getBean(RateLimitProperties.class)).isEqualTo(RateLimitProperties.defaults());
            assertThat(context.getBean(SpamProperties.class)).isEqualTo(SpamProperties.defaults());
            assertThat(context.getBean(ReportsProperties.class)).isEqualTo(ReportsProperties.defaults());
            assertThat(context.getBean(TrackbackProperties.class)).isEqualTo(TrackbackProperties.defaults());
            PrivacyProperties privacy = context.getBean(PrivacyProperties.class);
            assertThat(privacy.rightsRequestRetention()).isEqualTo(Duration.ofDays(365));
            assertThat(privacy.trackbackIpRetention()).isEqualTo(Duration.ofDays(90));
        });
    }

    @Test
    void boundValues() {
        runner.withPropertyValues("blog.ratelimit.comment-per-minute=1000",
                "blog.spam.duplicate-comment.window-minutes=1440", "blog.spam.duplicate-comment.max-count=100",
                "blog.reports.member-per-hour=100000", "blog.trackback.receive-limit=100000").run(context -> {
                    assertThat(context.getBean(RateLimitProperties.class).commentPerMinute()).isEqualTo(1000);
                    assertThat(context.getBean(SpamProperties.class).duplicateComment().windowMinutes())
                            .isEqualTo(1440);
                    assertThat(context.getBean(ReportsProperties.class).memberPerHour()).isEqualTo(100000);
                    assertThat(context.getBean(TrackbackProperties.class).receiveLimit()).isEqualTo(100000);
                });
    }

    @Test
    void invalidValuesFailStartup() {
        for (String bad : new String[] {"blog.ratelimit.post-publish-per-hour=0",
                "blog.ratelimit.comment-per-minute=0", "blog.ratelimit.guestbook-per-minute=-1",
                "blog.ratelimit.media-upload-per-minute=0", "blog.ratelimit.signup-per-ip-per-hour=0",
                "blog.spam.duplicate-comment.window-minutes=0", "blog.spam.duplicate-comment.max-count=1",
                "blog.spam.duplicate-comment.min-length=0", "blog.reports.member-per-hour=0",
                "blog.reports.rights-request-per-ip-per-hour=0", "blog.reports.penalty-window=0s",
                "blog.trackback.receive-limit=0", "blog.trackback.receive-window=0s",
                "blog.trackback.connect-timeout=0s", "blog.trackback.max-targets=0",
                "blog.trackback.executor-threads=0", "blog.privacy.rights-request-retention=0s",
                "blog.privacy.trackback-ip-retention=-1d"}) {
            runner.withPropertyValues(bad).run(c -> assertThat(c).as(bad).hasFailed());
        }
    }
}
