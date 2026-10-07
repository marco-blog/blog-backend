package net.java21.blog.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Period;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

/** 007 T014: {@code blog.external.*} 기본값과 잘못된 값이면 기동 실패(contracts/api.md "프로퍼티"). */
class ExternalFeedPropertiesTest {

    private static ExternalFeedProperties bind(Map<String, String> values) {
        Binder binder = new Binder(new MapConfigurationPropertySource(values));
        return binder.bindOrCreate("blog.external", ExternalFeedProperties.class);
    }

    @Test
    void defaultsMatchContract() {
        ExternalFeedProperties p = bind(Map.of());

        assertThat(p).isEqualTo(ExternalFeedProperties.defaults());
        assertThat(p.fetchThreads()).isEqualTo(4);
        assertThat(p.batchSize()).isEqualTo(50);
        assertThat(p.pollInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.fetchInterval()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.fetchJitter()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.leaseTime()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.maxRedirects()).isEqualTo(3);
        assertThat(p.maxFeedSize()).isEqualTo(DataSize.ofMegabytes(2));
        assertThat(p.maxPageSize()).isEqualTo(DataSize.ofMegabytes(1));
        assertThat(p.maxImageSize()).isEqualTo(DataSize.ofMegabytes(5));
        assertThat(p.initialWindow()).isEqualTo(Period.ofDays(30));
        assertThat(p.maxItemsPerFetch()).isEqualTo(100);
        assertThat(p.maxBackoff()).isEqualTo(Duration.ofHours(12));
        assertThat(p.stopAfter()).isEqualTo(Period.ofDays(7));
        assertThat(p.autoClassifyMinConfidence()).isEqualTo(0.7);
        assertThat(p.scoreWeight()).isEqualTo(1.0);
        assertThat(p.memberLimit()).isEqualTo(3);
        assertThat(p.previewPerHour()).isEqualTo(20);
        assertThat(p.verifyChecksPerHour()).isEqualTo(10);
        assertThat(p.verificationTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(p.clickDedupeWindow()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.releaseRetention()).isEqualTo(Period.ofDays(30));
        assertThat(p.linkCheckCron()).isEqualTo("0 30 4 * * MON");
        assertThat(p.linkCheckBatch()).isEqualTo(500);
        assertThat(p.cleanupCron()).isEqualTo("0 30 5 * * *");
        assertThat(p.forbiddenHosts()).containsExactly("blog.java21.net");
    }

    @Test
    void e2eValuesBind() {
        ExternalFeedProperties p = bind(Map.of("blog.external.poll-interval", "2s",
                "blog.external.fetch-interval", "PT5S", "blog.external.fetch-jitter", "PT0S",
                "blog.external.preview-per-hour", "1000", "blog.external.forbidden-hosts", "A.example, b.example"));

        assertThat(p.pollInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.fetchInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.fetchJitter()).isZero();
        assertThat(p.previewPerHour()).isEqualTo(1000);
        assertThat(p.forbiddenHosts()).containsExactly("a.example", "b.example");
    }

    @ParameterizedTest
    @CsvSource({
            "fetch-threads, 0", "batch-size, 0", "member-limit, 0", "poll-interval, 0s", "fetch-interval, -PT1M",
            "fetch-jitter, -PT1S", "lease-time, 0s", "connect-timeout, 0s", "request-timeout, 0s", "max-redirects, -1",
            "max-feed-size, 0B", "max-page-size, 0B", "max-image-size, 0B", "initial-window, P0D",
            "max-items-per-fetch, 0", "max-backoff, 0s", "stop-after, P0D", "auto-classify-min-confidence, 1.1",
            "auto-classify-min-confidence, -0.1", "score-weight, 11", "preview-per-hour, 0", "verify-checks-per-hour, 0",
            "verification-ttl, 0s", "click-dedupe-window, 0s", "release-retention, P0D", "link-check-batch, 0"})
    void invalidValuesFailStartup(String key, String value) {
        assertThatThrownBy(() -> bind(Map.of("blog.external." + key, value))).isInstanceOf(BindException.class);
    }

    @Test
    void baseUrlHostIsAlwaysSelf() {
        ExternalFeedProperties p = bind(Map.of("blog.external.forbidden-hosts", "other.example"));

        assertThat(p.selfHosts("http://localhost:5173")).containsExactlyInAnyOrder("other.example", "localhost");
        assertThat(ExternalFeedProperties.defaults().selfHosts("https://blog.java21.net"))
                .containsExactly("blog.java21.net");
        assertThat(ExternalFeedProperties.defaults().selfHosts(null)).containsExactly("blog.java21.net");
    }

    @Test
    void userAgentDefaultsToBaseUrl() {
        assertThat(ExternalFeedProperties.defaults().userAgent("https://blog.java21.net/"))
                .isEqualTo("java21-blog-feed/1.0 (+https://blog.java21.net/updates)");
        assertThat(bind(Map.of("blog.external.user-agent", "custom/1")).userAgent("https://x")).isEqualTo("custom/1");
        assertThat(ExternalFeedProperties.ofPool(1, 2).fetchThreads()).isEqualTo(1);
    }
}
