package net.java21.blog.backend.spam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import net.java21.blog.backend.report.ReportsProperties;
import net.java21.blog.backend.setting.SettingKey;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.trackback.TrackbackProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 005 T021: 종류별 한도는 운영 설정·프로퍼티에서. */
@ExtendWith(MockitoExtension.class)
class RateLimitPolicyTest {

    @Mock
    private RateLimiter limiter;
    @Mock
    private SystemSettingsService settings;

    private RateLimitPolicy policy() {
        return new RateLimitPolicy(limiter, settings, ReportsProperties.defaults(), TrackbackProperties.defaults());
    }

    @Test
    void writeLimitsComeFromSettings() {
        when(settings.intValue(SettingKey.RATELIMIT_POST_PUBLISH_PER_HOUR)).thenReturn(10);
        when(settings.intValue(SettingKey.RATELIMIT_COMMENT_PER_MINUTE)).thenReturn(5);
        when(settings.intValue(SettingKey.RATELIMIT_GUESTBOOK_PER_MINUTE)).thenReturn(3);
        when(settings.intValue(SettingKey.RATELIMIT_MEDIA_UPLOAD_PER_MINUTE)).thenReturn(30);
        when(settings.intValue(SettingKey.RATELIMIT_SIGNUP_PER_IP_PER_HOUR)).thenReturn(7);
        RateLimitPolicy policy = policy();

        assertThat(policy.limit(RateLimitKind.POST_PUBLISH)).isEqualTo(new RateLimitPolicy.Limit(10, Duration.ofHours(1)));
        assertThat(policy.limit(RateLimitKind.COMMENT)).isEqualTo(new RateLimitPolicy.Limit(5, Duration.ofMinutes(1)));
        assertThat(policy.limit(RateLimitKind.GUESTBOOK).limit()).isEqualTo(3);
        assertThat(policy.limit(RateLimitKind.MEDIA_UPLOAD).limit()).isEqualTo(30);
        assertThat(policy.limit(RateLimitKind.SIGNUP)).isEqualTo(new RateLimitPolicy.Limit(7, Duration.ofHours(1)));
    }

    @Test
    void reportAndTrackbackLimitsComeFromProperties() {
        RateLimitPolicy policy = policy();
        assertThat(policy.limit(RateLimitKind.REPORT)).isEqualTo(new RateLimitPolicy.Limit(30, Duration.ofHours(1)));
        assertThat(policy.limit(RateLimitKind.RIGHTS_REQUEST).limit()).isEqualTo(5);
        assertThat(policy.limit(RateLimitKind.TRACKBACK_RECEIVE))
                .isEqualTo(new RateLimitPolicy.Limit(10, Duration.ofMinutes(10)));
        assertThatThrownBy(() -> policy.limit(RateLimitKind.LOGIN_FAILURE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void checkAndTryAcquireDelegate() {
        when(limiter.tryAcquire(RateLimitKind.REPORT, "u:1", 30, Duration.ofHours(1))).thenReturn(true);
        RateLimitPolicy policy = policy();
        policy.check(RateLimitKind.RIGHTS_REQUEST, "ip:1");
        assertThat(policy.tryAcquire(RateLimitKind.REPORT, "u:1")).isTrue();
        verify(limiter).check(RateLimitKind.RIGHTS_REQUEST, "ip:1", 5, Duration.ofHours(1));
    }
}
