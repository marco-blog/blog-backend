package net.java21.blog.backend.post;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 글 설정.
 *
 * @param viewDedupTtl     같은 사람(회원 ID 또는 방문자 쿠키)의 재조회를 세지 않는 기간(FR-020, research R10). 003 끝까지 읽음도 같은 기간
 * @param viewDedupMaxSize 조회 중복 판단 캐시의 최대 항목 수(넘으면 오래된 것부터 버린다)
 * @param visitorCookie    익명 방문자 쿠키 이름(research R19). 조회수 API가 없으면 발급한다
 * @param visitorCookieMaxAge 방문자 쿠키 수명(research R19: 1년)
 * @param statsRetention   글 일별 통계({@code post_daily_stats}) 보관 기간(003 research P4: 90일)
 * @param unlockTtl        보호 글 열람 쿠키 수명(004 research B4: 30분)
 * @param passwordMaxFailures 보호 글·비회원 글 비밀번호 연속 실패 허용 수(004 research B3: 5회)
 * @param passwordLockDuration 연속 실패가 한도에 닿았을 때 막는 시간(004 research B3: 10분)
 * @param scheduleMaxAhead 예약 발행 시각의 최대 앞날(004 research B5: 365일)
 */
@ConfigurationProperties("blog.posts")
public record PostsProperties(
        @DefaultValue("30m") Duration viewDedupTtl,
        @DefaultValue("100000") long viewDedupMaxSize,
        @DefaultValue("visitor_id") String visitorCookie,
        @DefaultValue("365d") Duration visitorCookieMaxAge,
        @DefaultValue("90d") Duration statsRetention,
        @DefaultValue("30m") Duration unlockTtl,
        @DefaultValue("5") int passwordMaxFailures,
        @DefaultValue("10m") Duration passwordLockDuration,
        @DefaultValue("365d") Duration scheduleMaxAhead) {

    public static final Duration DEFAULT_STATS_RETENTION = Duration.ofDays(90);
    public static final Duration DEFAULT_UNLOCK_TTL = Duration.ofMinutes(30);
    public static final int DEFAULT_PASSWORD_MAX_FAILURES = 5;
    public static final Duration DEFAULT_PASSWORD_LOCK_DURATION = Duration.ofMinutes(10);
    public static final Duration DEFAULT_SCHEDULE_MAX_AHEAD = Duration.ofDays(365);

    /** 통계 보관 기간과 004 값은 기본값으로 둔다(통계를 다루지 않는 테스트용). */
    public PostsProperties(Duration viewDedupTtl, long viewDedupMaxSize, String visitorCookie,
            Duration visitorCookieMaxAge) {
        this(viewDedupTtl, viewDedupMaxSize, visitorCookie, visitorCookieMaxAge, DEFAULT_STATS_RETENTION);
    }

    /** 004 값(보호 글·비밀번호 시도·예약)은 기본값으로 둔다. */
    public PostsProperties(Duration viewDedupTtl, long viewDedupMaxSize, String visitorCookie,
            Duration visitorCookieMaxAge, Duration statsRetention) {
        this(viewDedupTtl, viewDedupMaxSize, visitorCookie, visitorCookieMaxAge, statsRetention, DEFAULT_UNLOCK_TTL,
                DEFAULT_PASSWORD_MAX_FAILURES, DEFAULT_PASSWORD_LOCK_DURATION, DEFAULT_SCHEDULE_MAX_AHEAD);
    }

    @ConstructorBinding
    public PostsProperties {
        requirePositive(statsRetention, "stats-retention");
        requirePositive(unlockTtl, "unlock-ttl");
        requirePositive(passwordLockDuration, "password-lock-duration");
        requirePositive(scheduleMaxAhead, "schedule-max-ahead");
        if (passwordMaxFailures < 1) {
            throw new IllegalArgumentException("blog.posts.password-max-failures must be at least 1");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("blog.posts." + name + " must be positive");
        }
    }
}
