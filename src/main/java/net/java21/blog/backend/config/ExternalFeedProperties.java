package net.java21.blog.backend.config;

import java.net.URI;
import java.time.Duration;
import java.time.Period;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 외부 블로그 피드 수집 설정(007 contracts/api.md "프로퍼티", research E1·E2). 잘못된 값이면 기동을 멈춘다.
 *
 * @param fetchThreads              동시에 받는 피드 수(수집 풀 core=max)
 * @param batchSize                 한 차례에 고르는 피드 수(수집 풀 대기열 크기)
 * @param pollInterval              수집할 차례인 피드를 고르는 주기
 * @param fetchInterval             수집 주기 기본값(운영 설정 {@code external.fetch-interval}이 우선)
 * @param fetchJitter               다음 수집 시각에 더하는 무작위 지연 상한
 * @param leaseTime                 고른 피드를 다시 고르지 않는 시간
 * @param connectTimeout            외부 연결 시간
 * @param requestTimeout            요청 하나 전체 시간
 * @param maxRedirects              리다이렉트 상한
 * @param maxFeedSize               피드 응답 상한
 * @param maxPageSize               블로그 HTML 상한
 * @param maxImageSize              대표 이미지 상한
 * @param initialWindow             최초 수집 범위
 * @param maxItemsPerFetch          한 번에 처리하는 항목 수
 * @param maxBackoff                실패 지연 상한
 * @param stopAfter                 연속 실패 자동 중지
 * @param autoClassifyMinConfidence 자동 분류 채택 기준 기본값
 * @param scoreWeight               외부 글 인기 점수 가중치 기본값
 * @param memberLimit               회원당 외부 블로그 수
 * @param previewPerHour            회원당 시간당 미리보기 수
 * @param verifyChecksPerHour       회원당 시간당 인증 확인 수
 * @param verificationTtl           인증 코드 유효 시간
 * @param clickDedupeWindow         같은 방문자 클릭 중복 제거 시간
 * @param releaseRetention          해제된 등록에서 내린 글 보관 기간
 * @param linkCheckCron             원문 링크 점검 주기
 * @param linkCheckBatch            한 번에 점검하는 글 수
 * @param cleanupCron               정리 작업 주기
 * @param forbiddenHosts            외부 블로그로 등록할 수 없는 호스트(하위 도메인 포함)
 * @param userAgent                 외부 요청의 User-Agent(비우면 {@code blog.base-url}로 만든다)
 */
@ConfigurationProperties("blog.external")
public record ExternalFeedProperties(
        @DefaultValue("4") int fetchThreads,
        @DefaultValue("50") int batchSize,
        @DefaultValue("1m") Duration pollInterval,
        @DefaultValue("PT30M") Duration fetchInterval,
        @DefaultValue("PT5M") Duration fetchJitter,
        @DefaultValue("PT10M") Duration leaseTime,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("10s") Duration requestTimeout,
        @DefaultValue("3") int maxRedirects,
        @DefaultValue("2MB") DataSize maxFeedSize,
        @DefaultValue("1MB") DataSize maxPageSize,
        @DefaultValue("5MB") DataSize maxImageSize,
        @DefaultValue("P30D") Period initialWindow,
        @DefaultValue("100") int maxItemsPerFetch,
        @DefaultValue("PT12H") Duration maxBackoff,
        @DefaultValue("P7D") Period stopAfter,
        @DefaultValue("0.7") double autoClassifyMinConfidence,
        @DefaultValue("1.0") double scoreWeight,
        @DefaultValue("3") int memberLimit,
        @DefaultValue("20") int previewPerHour,
        @DefaultValue("10") int verifyChecksPerHour,
        @DefaultValue("PT24H") Duration verificationTtl,
        @DefaultValue("PT30M") Duration clickDedupeWindow,
        @DefaultValue("P30D") Period releaseRetention,
        @DefaultValue("0 30 4 * * MON") String linkCheckCron,
        @DefaultValue("500") int linkCheckBatch,
        @DefaultValue("0 30 5 * * *") String cleanupCron,
        @DefaultValue("blog.java21.net") List<String> forbiddenHosts,
        @DefaultValue("") String userAgent) {

    public ExternalFeedProperties {
        positive("fetch-threads", fetchThreads);
        positive("batch-size", batchSize);
        positive("poll-interval", pollInterval);
        positive("fetch-interval", fetchInterval);
        if (fetchJitter == null || fetchJitter.isNegative()) {
            throw new IllegalArgumentException("blog.external.fetch-jitter must be >= 0");
        }
        positive("lease-time", leaseTime);
        positive("connect-timeout", connectTimeout);
        positive("request-timeout", requestTimeout);
        if (maxRedirects < 0) {
            throw new IllegalArgumentException("blog.external.max-redirects must be >= 0");
        }
        positive("max-feed-size", maxFeedSize);
        positive("max-page-size", maxPageSize);
        positive("max-image-size", maxImageSize);
        positive("initial-window", initialWindow);
        positive("max-items-per-fetch", maxItemsPerFetch);
        positive("max-backoff", maxBackoff);
        positive("stop-after", stopAfter);
        if (!(autoClassifyMinConfidence >= 0 && autoClassifyMinConfidence <= 1)) {
            throw new IllegalArgumentException("blog.external.auto-classify-min-confidence must be 0..1");
        }
        if (!(scoreWeight >= 0 && scoreWeight <= 10)) {
            throw new IllegalArgumentException("blog.external.score-weight must be 0..10");
        }
        positive("member-limit", memberLimit);
        positive("preview-per-hour", previewPerHour);
        positive("verify-checks-per-hour", verifyChecksPerHour);
        positive("verification-ttl", verificationTtl);
        positive("click-dedupe-window", clickDedupeWindow);
        positive("release-retention", releaseRetention);
        positive("link-check-batch", linkCheckBatch);
        forbiddenHosts = forbiddenHosts == null ? List.of()
                : forbiddenHosts.stream().filter(h -> h != null && !h.isBlank())
                        .map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        userAgent = userAgent == null ? "" : userAgent.strip();
    }

    /** 기본값(시험용). */
    public static ExternalFeedProperties defaults() {
        return new ExternalFeedProperties(4, 50, Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofMinutes(5),
                Duration.ofMinutes(10), Duration.ofSeconds(5), Duration.ofSeconds(10), 3, DataSize.ofMegabytes(2),
                DataSize.ofMegabytes(1), DataSize.ofMegabytes(5), Period.ofDays(30), 100, Duration.ofHours(12),
                Period.ofDays(7), 0.7, 1.0, 3, 20, 10, Duration.ofHours(24), Duration.ofMinutes(30), Period.ofDays(30),
                "0 30 4 * * MON", 500, "0 30 5 * * *", List.of("blog.java21.net"), "");
    }

    /** 수집 풀 크기만 바꾼 기본값(시험용). */
    public static ExternalFeedProperties ofPool(int fetchThreads, int batchSize) {
        ExternalFeedProperties d = defaults();
        return new ExternalFeedProperties(fetchThreads, batchSize, d.pollInterval, d.fetchInterval, d.fetchJitter,
                d.leaseTime, d.connectTimeout, d.requestTimeout, d.maxRedirects, d.maxFeedSize, d.maxPageSize,
                d.maxImageSize, d.initialWindow, d.maxItemsPerFetch, d.maxBackoff, d.stopAfter,
                d.autoClassifyMinConfidence, d.scoreWeight, d.memberLimit, d.previewPerHour, d.verifyChecksPerHour,
                d.verificationTtl, d.clickDedupeWindow, d.releaseRetention, d.linkCheckCron, d.linkCheckBatch,
                d.cleanupCron, d.forbiddenHosts, d.userAgent);
    }

    /** 우리 서비스 호스트: {@code forbidden-hosts}와 {@code blog.base-url}의 호스트(항상 포함). */
    public Set<String> selfHosts(String baseUrl) {
        Set<String> hosts = new LinkedHashSet<>(forbiddenHosts);
        String host = baseUrl == null ? null : URI.create(baseUrl.strip()).getHost();
        if (host != null && !host.isBlank()) {
            hosts.add(host.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(hosts);
    }

    /** User-Agent(비어 있으면 {@code java21-blog-feed/1.0 (+{base-url}/updates)}). */
    public String userAgent(String baseUrl) {
        if (!userAgent.isEmpty()) {
            return userAgent;
        }
        String base = baseUrl == null ? "" : baseUrl.strip().replaceAll("/+$", "");
        return "java21-blog-feed/1.0 (+" + base + "/updates)";
    }

    private static void positive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException("blog.external." + name + " must be >= 1");
        }
    }

    private static void positive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("blog.external." + name + " must be positive");
        }
    }

    private static void positive(String name, Period value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("blog.external." + name + " must be positive");
        }
    }

    private static void positive(String name, DataSize value) {
        if (value == null || value.toBytes() <= 0) {
            throw new IllegalArgumentException("blog.external." + name + " must be positive");
        }
    }
}
