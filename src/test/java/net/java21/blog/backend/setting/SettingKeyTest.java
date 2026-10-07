package net.java21.blog.backend.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.spam.RateLimitProperties;
import net.java21.blog.backend.spam.SpamProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/** 005 T011: {@code ratelimit.*} 5개와 {@code spam.duplicate-comment}의 검증·기본값. */
class SettingKeyTest {

    @ParameterizedTest
    @EnumSource(value = SettingKey.class, names = "RATELIMIT_.*", mode = EnumSource.Mode.MATCH_ALL)
    void rateLimitKeysAcceptPositiveIntegersOnly(SettingKey key) {
        int max = key == SettingKey.RATELIMIT_SIGNUP_PER_IP_PER_HOUR ? 100_000 : 10_000;
        assertThat(key.normalize(1)).isEqualTo(1);
        assertThat(key.normalize(max)).isEqualTo(max);
        assertThat(key.normalize(7.0)).isEqualTo(7);
        for (Object bad : new Object[] {0, -1, max + 1, 1.5, "5", null}) {
            assertThatThrownBy(() -> key.normalize(bad)).isInstanceOfSatisfying(BusinessException.class, e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                FieldError error = e.fieldErrors().get(0);
                assertThat(error.field()).isEqualTo("value");
                assertThat(error.code()).isEqualTo("INVALID");
                assertThat(error.params()).containsEntry("min", 1).containsEntry("max", max);
            });
        }
    }

    @Test
    void rateLimitDefaultsComeFromProperties() {
        SettingDefaults defaults = new SettingDefaults(PortalProperties.defaults(),
                new RateLimitProperties(11, 12, 13, 14, 15), SpamProperties.defaults());
        assertThat(SettingKey.RATELIMIT_POST_PUBLISH_PER_HOUR.defaultValue(defaults)).isEqualTo(11);
        assertThat(SettingKey.RATELIMIT_COMMENT_PER_MINUTE.defaultValue(defaults)).isEqualTo(12);
        assertThat(SettingKey.RATELIMIT_GUESTBOOK_PER_MINUTE.defaultValue(defaults)).isEqualTo(13);
        assertThat(SettingKey.RATELIMIT_MEDIA_UPLOAD_PER_MINUTE.defaultValue(defaults)).isEqualTo(14);
        assertThat(SettingKey.RATELIMIT_SIGNUP_PER_IP_PER_HOUR.defaultValue(defaults)).isEqualTo(15);
        assertThat(SettingKey.find("ratelimit.comment-per-minute")).contains(SettingKey.RATELIMIT_COMMENT_PER_MINUTE);
    }

    @Test
    void duplicateCommentValidatesBothFields() {
        SettingKey key = SettingKey.SPAM_DUPLICATE_COMMENT;
        assertThat(key.normalize(Map.of("windowMinutes", 1440, "maxCount", 2, "extra", 1)))
                .isEqualTo(Map.of("windowMinutes", 1440, "maxCount", 2));
        assertThat(key.defaultValue(SettingDefaults.of(PortalProperties.defaults())))
                .isEqualTo(Map.of("windowMinutes", 10, "maxCount", 3));

        assertThatThrownBy(() -> key.normalize(Map.of("windowMinutes", 0, "maxCount", 101)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors())
                        .extracting(FieldError::field, FieldError::code)
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("value.windowMinutes", "INVALID"),
                                org.assertj.core.groups.Tuple.tuple("value.maxCount", "INVALID")));
        Map<String, Object> missing = new HashMap<>();
        missing.put("windowMinutes", 5);
        assertThatThrownBy(() -> key.normalize(missing)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).extracting(FieldError::field, FieldError::code)
                        .containsExactly(org.assertj.core.groups.Tuple.tuple("value.maxCount", "REQUIRED")));
        assertThatThrownBy(() -> key.normalize("10")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> key.normalize(Map.of("windowMinutes", "10", "maxCount", 3)))
                .isInstanceOf(BusinessException.class);
    }
}
