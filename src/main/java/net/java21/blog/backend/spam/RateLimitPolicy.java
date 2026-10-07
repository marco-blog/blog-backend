package net.java21.blog.backend.spam;

import java.time.Duration;

import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.report.ReportsProperties;
import net.java21.blog.backend.setting.SettingKey;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.trackback.TrackbackProperties;
import org.springframework.stereotype.Component;

/**
 * 종류별 한도와 창(005 research M8). 작성 한도는 운영 설정({@code ratelimit.*}, 003 {@link SystemSettingsService}가 캐시하고 바뀌면 커밋 뒤
 * 비움)에서, 신고·트랙백 받기 한도는 프로퍼티에서 읽는다. 로그인 실패({@code LOGIN_FAILURE})와 반복 내용({@code DUPLICATE_CONTENT})은
 * 각 장치가 따로 다룬다.
 */
@Component
public class RateLimitPolicy {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    /** 한도와 창. */
    public record Limit(int limit, Duration window) {
    }

    private final RateLimiter limiter;
    private final SystemSettingsService settings;
    private final ReportsProperties reports;
    private final TrackbackProperties trackback;
    private final ExternalFeedProperties external;

    public RateLimitPolicy(RateLimiter limiter, SystemSettingsService settings, ReportsProperties reports,
            TrackbackProperties trackback, ExternalFeedProperties external) {
        this.limiter = limiter;
        this.settings = settings;
        this.reports = reports;
        this.trackback = trackback;
        this.external = external;
    }

    /** 지금 쓰는 한도. */
    public Limit limit(RateLimitKind kind) {
        return switch (kind) {
            case POST_PUBLISH -> new Limit(settings.intValue(SettingKey.RATELIMIT_POST_PUBLISH_PER_HOUR), HOUR);
            case COMMENT -> new Limit(settings.intValue(SettingKey.RATELIMIT_COMMENT_PER_MINUTE), MINUTE);
            case GUESTBOOK -> new Limit(settings.intValue(SettingKey.RATELIMIT_GUESTBOOK_PER_MINUTE), MINUTE);
            case MEDIA_UPLOAD -> new Limit(settings.intValue(SettingKey.RATELIMIT_MEDIA_UPLOAD_PER_MINUTE), MINUTE);
            case SIGNUP -> new Limit(settings.intValue(SettingKey.RATELIMIT_SIGNUP_PER_IP_PER_HOUR), HOUR);
            case REPORT -> new Limit(reports.memberPerHour(), HOUR);
            case RIGHTS_REQUEST -> new Limit(reports.rightsRequestPerIpPerHour(), HOUR);
            case TRACKBACK_RECEIVE -> new Limit(trackback.receiveLimit(), trackback.receiveWindow());
            // 007 외부 블로그(research E8): 회원별 시간당
            case EXTERNAL_PREVIEW -> new Limit(external.previewPerHour(), HOUR);
            case EXTERNAL_VERIFY_CHECK -> new Limit(external.verifyChecksPerHour(), HOUR);
            case LOGIN_FAILURE, DUPLICATE_CONTENT ->
                throw new IllegalArgumentException(kind + " has no fixed rate limit policy");
        };
    }

    /**
     * 한 번 센다.
     *
     * @throws net.java21.blog.backend.common.error.BusinessException 429 {@code TOO_MANY_REQUESTS} + {@code Retry-After}
     */
    public void check(RateLimitKind kind, String subject) {
        Limit limit = limit(kind);
        limiter.check(kind, subject, limit.limit(), limit.window());
    }

    /** 한 번 세고 한도 안이면 true. */
    public boolean tryAcquire(RateLimitKind kind, String subject) {
        Limit limit = limit(kind);
        return limiter.tryAcquire(kind, subject, limit.limit(), limit.window());
    }
}
