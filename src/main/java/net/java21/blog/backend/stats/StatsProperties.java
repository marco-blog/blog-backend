package net.java21.blog.backend.stats;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 방문자 수·월별 보관함 설정(004 contracts/api.md "프로퍼티", research B9·B12). 잘못된 값이면 기동하지 않는다.
 *
 * @param timeZone             방문 날짜·월별 보관함의 날짜 기준 시간대(기본 Asia/Seoul)
 * @param visitDedupMaxSize    방문 중복 제거 캐시의 최대 항목 수
 * @param botUserAgentPattern  세지 않는 User-Agent 정규식(빈 User-Agent도 세지 않는다)
 */
@ConfigurationProperties("blog.stats")
public record StatsProperties(
        @DefaultValue("Asia/Seoul") String timeZone,
        @DefaultValue("200000") long visitDedupMaxSize,
        @DefaultValue(StatsProperties.DEFAULT_BOT_PATTERN) String botUserAgentPattern) {

    public static final String DEFAULT_BOT_PATTERN = "(?i)(bot|crawler|spider|slurp|facebookexternalhit|preview)";

    public StatsProperties {
        zoneOf(timeZone);
        if (visitDedupMaxSize < 1) {
            throw new IllegalArgumentException("blog.stats.visit-dedup-max-size must be at least 1");
        }
        patternOf(botUserAgentPattern);
    }

    /** 기본값(테스트용). */
    public static StatsProperties defaults() {
        return new StatsProperties("Asia/Seoul", 200_000, DEFAULT_BOT_PATTERN);
    }

    public ZoneId zone() {
        return zoneOf(timeZone);
    }

    public Pattern botPattern() {
        return patternOf(botUserAgentPattern);
    }

    private static ZoneId zoneOf(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("blog.stats.time-zone is required");
        }
        try {
            return ZoneId.of(value);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("blog.stats.time-zone is not a valid zone: " + value, e);
        }
    }

    private static Pattern patternOf(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("blog.stats.bot-user-agent-pattern is required");
        }
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("blog.stats.bot-user-agent-pattern is not a valid regex", e);
        }
    }
}
