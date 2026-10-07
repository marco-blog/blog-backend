package net.java21.blog.backend.setting.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.portal.service.ScoreWeights;
import net.java21.blog.backend.setting.SettingKey;
import net.java21.blog.backend.setting.domain.SystemSetting;
import net.java21.blog.backend.setting.repository.SystemSettingRepository;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** 운영 설정값(T007, research P3): 행이 없으면 프로퍼티 기본값, 키별 형식·범위 검증, 모르는 키 404, 저장·삭제 후 캐시 무효화와 이벤트. */
@ExtendWith(MockitoExtension.class)
class SystemSettingsServiceTest {

    @Mock
    private SystemSettingRepository repository;
    @Mock
    private ApplicationEventPublisher events;

    private final List<SystemSetting> rows = new ArrayList<>();
    private SystemSettingsService service;
    private User admin;

    @BeforeEach
    void setUp() {
        service = new SystemSettingsService(repository, net.java21.blog.backend.setting.SettingDefaults.of(PortalProperties.defaults()), events);
        admin = TestEntities.user(9L);
        org.mockito.Mockito.lenient().when(repository.findAll()).thenAnswer(i -> List.copyOf(rows));
    }

    @Test
    void defaultsComeFromPropertiesWhenThereAreNoRows() {
        assertThat(service.scoreWeights()).isEqualTo(new ScoreWeights(1, 5, 10, 8, 48, 0.5));
        assertThat(service.newMemberDelay()).isEqualTo(Duration.ofHours(24));
        assertThat(service.minContentLength()).isEqualTo(200);
        assertThat(service.topicAutoHideThreshold()).isEqualTo(20);
        assertThat(service.value(SettingKey.PORTAL_NEW_MEMBER_DELAY)).isEqualTo("PT24H");
        assertThat(service.isOverridden(SettingKey.PORTAL_MIN_CONTENT_LENGTH)).isFalse();
        assertThat(service.value(SettingKey.PORTAL_SCORE_WEIGHTS)).isEqualTo(Map.of("view", 1, "readComplete", 5,
                "like", 10, "comment", 8, "halfLifeHours", 48, "reportPenalty", 0.5));
    }

    @Test
    void rowsOverrideDefaultsAndAreCachedUntilInvalidated() {
        rows.add(new SystemSetting("portal.min-content-length", 500, admin));
        rows.add(new SystemSetting("portal.new-member-delay", "PT0S", admin));
        rows.add(new SystemSetting("portal.topic-auto-hide-threshold", 1, admin));
        rows.add(new SystemSetting("portal.score-weights", Map.of("view", 0, "readComplete", 1, "like", 2,
                "comment", 3, "halfLifeHours", 24, "reportPenalty", 1), admin));

        assertThat(service.minContentLength()).isEqualTo(500);
        assertThat(service.newMemberDelay()).isZero();
        assertThat(service.topicAutoHideThreshold()).isEqualTo(1);
        assertThat(service.scoreWeights()).isEqualTo(new ScoreWeights(0, 1, 2, 3, 24, 1));
        assertThat(service.isOverridden(SettingKey.PORTAL_MIN_CONTENT_LENGTH)).isTrue();
        verify(repository, times(1)).findAll();

        rows.clear();
        assertThat(service.minContentLength()).isEqualTo(500);
        service.onPortalChanged(new PortalChangedEvent("test"));
        assertThat(service.minContentLength()).isEqualTo(200);
        verify(repository, times(2)).findAll();
    }

    @Test
    void invalidStoredValueFallsBackToDefault() {
        rows.add(new SystemSetting("portal.min-content-length", "many", admin));

        assertThat(service.minContentLength()).isEqualTo(200);
    }

    @Test
    void setCreatesOrChangesRowInvalidatesAndPublishesEvent() {
        when(repository.findById("portal.min-content-length")).thenReturn(Optional.empty());
        assertThat(service.minContentLength()).isEqualTo(200);

        assertThat(service.set(SettingKey.PORTAL_MIN_CONTENT_LENGTH, 300, admin)).isEqualTo(300);

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getValue()).isEqualTo(300);
        assertThat(saved.getValue().getUpdatedBy()).isSameAs(admin);
        rows.add(saved.getValue());
        assertThat(service.minContentLength()).isEqualTo(300);
        verify(events).publishEvent(new PortalChangedEvent("setting:portal.min-content-length"));

        SystemSetting existing = saved.getValue();
        when(repository.findById("portal.min-content-length")).thenReturn(Optional.of(existing));
        service.set(SettingKey.PORTAL_MIN_CONTENT_LENGTH, 400, admin);
        assertThat(existing.getValue()).isEqualTo(400);
        assertThat(service.minContentLength()).isEqualTo(400);
        verify(repository, times(1)).save(any());
    }

    @Test
    void resetDeletesRowAndPublishesEvent() {
        SystemSetting row = new SystemSetting("portal.topic-auto-hide-threshold", 3, admin);
        rows.add(row);
        when(repository.findById("portal.topic-auto-hide-threshold")).thenReturn(Optional.of(row));
        assertThat(service.topicAutoHideThreshold()).isEqualTo(3);

        service.reset(SettingKey.PORTAL_TOPIC_AUTO_HIDE_THRESHOLD);
        rows.clear();

        verify(repository).delete(row);
        assertThat(service.topicAutoHideThreshold()).isEqualTo(20);
        verify(events).publishEvent(new PortalChangedEvent("setting:portal.topic-auto-hide-threshold"));

        when(repository.findById("portal.topic-auto-hide-threshold")).thenReturn(Optional.empty());
        service.reset(SettingKey.PORTAL_TOPIC_AUTO_HIDE_THRESHOLD);
        verify(repository, times(1)).delete(any());
    }

    @Test
    void invalidValueIsRejectedBeforeSaving() {
        assertCode(() -> service.set(SettingKey.PORTAL_MIN_CONTENT_LENGTH, -1, admin), ErrorCode.VALIDATION_FAILED);
        verify(repository, never()).save(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void scoreWeightsValidation() {
        Map<String, Object> ok = new LinkedHashMap<>(Map.of("view", 1, "readComplete", 5, "like", 10, "comment", 8,
                "halfLifeHours", 48, "reportPenalty", 0.5));
        assertThat(SettingKey.PORTAL_SCORE_WEIGHTS.normalize(ok)).isEqualTo(ok);

        Map<String, Object> missing = new LinkedHashMap<>(ok);
        missing.remove("like");
        assertThat(errors(SettingKey.PORTAL_SCORE_WEIGHTS, missing))
                .containsExactly(FieldError.of("value.like", "REQUIRED"));

        Map<String, Object> negative = new LinkedHashMap<>(ok);
        negative.put("view", -1);
        negative.put("halfLifeHours", 0);
        negative.put("reportPenalty", 1.5);
        negative.put("comment", "eight");
        assertThat(errors(SettingKey.PORTAL_SCORE_WEIGHTS, negative)).containsExactly(
                new FieldError("value.view", "INVALID", Map.of("min", 0, "max", 1000)),
                new FieldError("value.comment", "INVALID", Map.of("min", 0, "max", 1000)),
                new FieldError("value.halfLifeHours", "INVALID", Map.of("min", 1, "max", 720)),
                new FieldError("value.reportPenalty", "INVALID", Map.of("min", 0, "max", 1)));
        assertThat(errors(SettingKey.PORTAL_SCORE_WEIGHTS, "x"))
                .containsExactly(new FieldError("value", "INVALID", Map.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT24H", "P30D", "PT30M"})
    void newMemberDelayAcceptsIsoDurationsInRange(String value) {
        assertThat(SettingKey.PORTAL_NEW_MEMBER_DELAY.normalize(value)).isEqualTo(Duration.parse(value).toString());
    }

    @Test
    void newMemberDelayRejectsBadFormatAndRange() {
        FieldError expected = new FieldError("value", "INVALID", Map.of("min", "PT0S", "max", "P30D"));
        assertThat(errors(SettingKey.PORTAL_NEW_MEMBER_DELAY, "24h")).containsExactly(expected);
        assertThat(errors(SettingKey.PORTAL_NEW_MEMBER_DELAY, "P31D")).containsExactly(expected);
        assertThat(errors(SettingKey.PORTAL_NEW_MEMBER_DELAY, "-PT1H")).containsExactly(expected);
        assertThat(errors(SettingKey.PORTAL_NEW_MEMBER_DELAY, 24)).containsExactly(expected);
    }

    @Test
    void integerSettingsCheckRange() {
        assertThat(SettingKey.PORTAL_MIN_CONTENT_LENGTH.normalize(10000)).isEqualTo(10000);
        assertThat(SettingKey.PORTAL_MIN_CONTENT_LENGTH.normalize(0)).isEqualTo(0);
        assertThat(SettingKey.PORTAL_TOPIC_AUTO_HIDE_THRESHOLD.normalize(1000.0)).isEqualTo(1000);
        assertThat(errors(SettingKey.PORTAL_MIN_CONTENT_LENGTH, 10001))
                .containsExactly(new FieldError("value", "INVALID", Map.of("min", 0, "max", 10000)));
        assertThat(errors(SettingKey.PORTAL_TOPIC_AUTO_HIDE_THRESHOLD, 1.5))
                .containsExactly(new FieldError("value", "INVALID", Map.of("min", 0, "max", 1000)));
        assertThat(errors(SettingKey.PORTAL_TOPIC_AUTO_HIDE_THRESHOLD, "20"))
                .containsExactly(new FieldError("value", "INVALID", Map.of("min", 0, "max", 1000)));
    }

    @Test
    void unknownKeyIsSettingNotFound() {
        assertCode(() -> SettingKey.require("portal.nope"), ErrorCode.SETTING_NOT_FOUND);
        assertThat(SettingKey.require("portal.score-weights")).isEqualTo(SettingKey.PORTAL_SCORE_WEIGHTS);
        assertThat(SettingKey.find("ratelimit.x")).isEmpty();
        assertThat(SettingKey.PORTAL_MIN_CONTENT_LENGTH.key()).isEqualTo("portal.min-content-length");
    }

    @Test
    void scoreWeightsJsonRoundTrip() {
        ScoreWeights weights = new ScoreWeights(1.5, 5, 10, 8, 48, 0.25);
        assertThat(ScoreWeights.fromJson(weights.toJson())).isEqualTo(weights);
        assertThat(weights.toJson()).containsEntry("view", 1.5).containsEntry("readComplete", 5);
        assertThatThrownBy(() -> ScoreWeights.fromJson(Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    private static List<FieldError> errors(SettingKey key, Object raw) {
        try {
            key.normalize(raw);
        } catch (BusinessException e) {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            return e.fieldErrors();
        }
        throw new AssertionError("expected VALIDATION_FAILED");
    }
}
