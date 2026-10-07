package net.java21.blog.backend.admin.setting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.setting.dto.SettingResponse;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.setting.SettingKey;
import net.java21.blog.backend.setting.domain.SystemSetting;
import net.java21.blog.backend.setting.repository.SystemSettingRepository;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 운영 설정(003 T086): 키 목록·기본값·덮어쓴 값, 저장·기본값으로의 작업 기록(target_key), 모르는 키 404, 값 필수. */
@ExtendWith(MockitoExtension.class)
class AdminSettingServiceTest {

    private static final long ADMIN = 9L;
    private static final String IP = "::1";
    private static final String MIN = "portal.min-content-length";

    @Mock
    private SystemSettingsService settings;
    @Mock
    private SystemSettingRepository repository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AdminAuditService auditService;

    private final PortalProperties properties = PortalProperties.defaults();
    private AdminSettingService service;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new AdminSettingService(settings, repository, net.java21.blog.backend.setting.SettingDefaults.of(properties), userRepository, auditService);
        admin = TestEntities.user(ADMIN);
        TestEntities.with(admin, "nickname", "관리자");
        lenient().when(userRepository.getReferenceById(ADMIN)).thenReturn(admin);
    }

    @Test
    void listShowsAllKnownKeysSortedWithDefaultsAndOverrides() {
        SystemSetting row = new SystemSetting(MIN, 500, admin);
        TestEntities.with(row, "updatedAt", Instant.parse("2026-10-01T00:00:00Z"));
        when(repository.findAllWithUpdatedBy()).thenReturn(List.of(row));
        when(settings.value(SettingKey.PORTAL_MIN_CONTENT_LENGTH)).thenReturn(500);

        assertThat(service.list(null)).hasSize(SettingKey.values().length);
        List<SettingResponse> all = service.list("portal.");

        assertThat(all).extracting(SettingResponse::key).containsExactly("portal.min-content-length",
                "portal.new-member-delay", "portal.score-weights", "portal.topic-auto-hide-threshold");
        SettingResponse min = all.get(0);
        assertThat(min.value()).isEqualTo(500);
        assertThat(min.defaultValue()).isEqualTo(properties.minContentLength());
        assertThat(min.overridden()).isTrue();
        assertThat(min.updatedBy().nickname()).isEqualTo("관리자");
        assertThat(min.updatedAt()).isNotNull();
        SettingResponse delay = all.get(1);
        assertThat(delay.overridden()).isFalse();
        assertThat(delay.value()).isEqualTo(properties.newMemberDelay().toString());
        assertThat(delay.updatedBy()).isNull();

        assertThat(service.list("portal.score")).extracting(SettingResponse::key)
                .containsExactly("portal.score-weights");
        assertThat(service.list("other.")).isEmpty();
    }

    @Test
    void rateLimitKeysDefaultToProperties() {
        when(repository.findAllWithUpdatedBy()).thenReturn(List.of());

        List<SettingResponse> limits = service.list("ratelimit.");

        assertThat(limits).extracting(SettingResponse::key).containsExactly("ratelimit.comment-per-minute",
                "ratelimit.guestbook-per-minute", "ratelimit.media-upload-per-minute",
                "ratelimit.post-publish-per-hour", "ratelimit.signup-per-ip-per-hour");
        assertThat(limits).extracting(SettingResponse::defaultValue).containsExactly(5, 3, 30, 10, 5);
        assertThat(service.list("spam.")).singleElement()
                .satisfies(r -> assertThat(r.defaultValue()).isEqualTo(Map.of("windowMinutes", 10, "maxCount", 3)));
    }

    @Test
    void setRecordsBeforeAndAfter() {
        when(settings.isOverridden(SettingKey.PORTAL_MIN_CONTENT_LENGTH)).thenReturn(false);
        when(settings.set(SettingKey.PORTAL_MIN_CONTENT_LENGTH, 300, admin)).thenReturn(300);
        SystemSetting row = new SystemSetting(MIN, 300, admin);
        when(repository.findAllWithUpdatedBy()).thenReturn(List.of(row));
        when(settings.value(SettingKey.PORTAL_MIN_CONTENT_LENGTH)).thenReturn(300);

        SettingResponse response = service.set(ADMIN, MIN, 300, IP);

        assertThat(response.value()).isEqualTo(300);
        assertThat(response.overridden()).isTrue();
        verify(auditService).recordKey(ADMIN, AuditActions.SETTING_CHANGE, AuditActions.TARGET_SETTING, MIN,
                nullValue(), Map.of("value", 300), IP);
    }

    @Test
    void resetRecordsPreviousValue() {
        when(settings.isOverridden(SettingKey.PORTAL_MIN_CONTENT_LENGTH)).thenReturn(true);
        when(settings.value(SettingKey.PORTAL_MIN_CONTENT_LENGTH)).thenReturn(300);

        SettingResponse response = service.reset(ADMIN, MIN, IP);

        assertThat(response.overridden()).isFalse();
        assertThat(response.value()).isEqualTo(properties.minContentLength());
        verify(settings).reset(SettingKey.PORTAL_MIN_CONTENT_LENGTH);
        verify(auditService).recordKey(ADMIN, AuditActions.SETTING_CHANGE, AuditActions.TARGET_SETTING, MIN,
                Map.of("value", 300), nullValue(), IP);
    }

    @Test
    void unknownKeyAndMissingValueAreRejected() {
        assertThatThrownBy(() -> service.set(ADMIN, "nope", 1, IP)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SETTING_NOT_FOUND));
        assertThatThrownBy(() -> service.reset(ADMIN, "nope", IP)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SETTING_NOT_FOUND));
        assertThatThrownBy(() -> service.set(ADMIN, MIN, null, IP)).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors().get(0).field()).isEqualTo("value"));
        verify(settings, never()).set(any(), any(), any());
    }

    private static Map<String, Object> nullValue() {
        Map<String, Object> map = new HashMap<>();
        map.put("value", null);
        return map;
    }
}
