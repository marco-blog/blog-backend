package net.java21.blog.backend.setting.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.portal.service.ScoreWeights;
import net.java21.blog.backend.setting.SettingDefaults;
import net.java21.blog.backend.setting.SettingKey;
import net.java21.blog.backend.setting.domain.SystemSetting;
import net.java21.blog.backend.setting.repository.SystemSettingRepository;
import net.java21.blog.backend.user.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 운영 설정값(003 research P3). {@code system_settings} 행이 있으면 그 값, 없으면 프로퍼티({@link SettingDefaults}) 기본값.
 * 모든 행을 메모리에 캐시하고(행은 몇 개뿐), 바꾸면 커밋 뒤 캐시를 비우고 {@link PortalChangedEvent}로 포털 캐시도 비운다.
 * 저장된 값이 형식에 맞지 않으면(손으로 고친 행 등) 경고를 남기고 기본값을 쓴다.
 */
@Service
public class SystemSettingsService {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingsService.class);

    private final SystemSettingRepository repository;
    private final SettingDefaults defaults;
    private final ApplicationEventPublisher events;
    private final AtomicReference<Map<String, Object>> overrides = new AtomicReference<>();

    public SystemSettingsService(SystemSettingRepository repository, SettingDefaults defaults,
            ApplicationEventPublisher events) {
        this.repository = repository;
        this.defaults = defaults;
        this.events = events;
    }

    /** 지금 쓰는 값(JSON 모양). */
    public Object value(SettingKey key) {
        Object stored = overrides().get(key.key());
        if (stored != null) {
            try {
                return key.normalize(stored);
            } catch (RuntimeException e) {
                log.warn("Ignoring invalid stored setting {}: {}", key.key(), stored);
            }
        }
        return key.defaultValue(defaults);
    }

    /** 행이 있는지(관리자 화면의 "기본값과 다름"). */
    public boolean isOverridden(SettingKey key) {
        return overrides().containsKey(key.key());
    }

    public ScoreWeights scoreWeights() {
        return ScoreWeights.fromJson((Map<?, ?>) value(SettingKey.PORTAL_SCORE_WEIGHTS));
    }

    public Duration newMemberDelay() {
        return Duration.parse((String) value(SettingKey.PORTAL_NEW_MEMBER_DELAY));
    }

    public int minContentLength() {
        return ((Number) value(SettingKey.PORTAL_MIN_CONTENT_LENGTH)).intValue();
    }

    public int topicAutoHideThreshold() {
        return ((Number) value(SettingKey.PORTAL_TOPIC_AUTO_HIDE_THRESHOLD)).intValue();
    }

    /** 외부 블로그 수집 주기(007 {@code external.fetch-interval}). */
    public Duration externalFetchInterval() {
        return Duration.parse((String) value(SettingKey.EXTERNAL_FETCH_INTERVAL));
    }

    /** 자동 분류 채택 기준(007 {@code external.auto-classify-min-confidence}). */
    public double autoClassifyMinConfidence() {
        return ((Number) value(SettingKey.EXTERNAL_AUTO_CLASSIFY_MIN_CONFIDENCE)).doubleValue();
    }

    /** 외부 글 인기 점수 가중치(007 {@code external.score-weight}). */
    public double externalScoreWeight() {
        return ((Number) value(SettingKey.EXTERNAL_SCORE_WEIGHT)).doubleValue();
    }

    /** 정수 값 키(예: {@code ratelimit.*})의 지금 값. */
    public int intValue(SettingKey key) {
        return ((Number) value(key)).intValue();
    }

    /** 반복 스팸 기준(창 분, 허용 수). */
    public DuplicateRule duplicateRule() {
        Map<?, ?> value = (Map<?, ?>) value(SettingKey.SPAM_DUPLICATE_COMMENT);
        return new DuplicateRule(((Number) value.get("windowMinutes")).intValue(),
                ((Number) value.get("maxCount")).intValue());
    }

    /** {@code spam.duplicate-comment} 값. */
    public record DuplicateRule(int windowMinutes, int maxCount) {
    }

    /** 값을 검증해 저장한다(없으면 만들고 있으면 바꾼다). @return 저장한 값 */
    @Transactional
    public Object set(SettingKey key, Object raw, User admin) {
        Object normalized = key.normalize(raw);
        repository.findById(key.key()).ifPresentOrElse(row -> row.change(normalized, admin),
                () -> repository.save(new SystemSetting(key.key(), normalized, admin)));
        changed(key);
        return normalized;
    }

    /** 기본값으로 되돌린다(행 삭제). 행이 없어도 된다. */
    @Transactional
    public void reset(SettingKey key) {
        repository.findById(key.key()).ifPresent(repository::delete);
        changed(key);
    }

    /** 커밋 뒤(트랜잭션 밖이면 바로) 캐시를 비운다. */
    @TransactionalEventListener(fallbackExecution = true)
    public void onPortalChanged(PortalChangedEvent event) {
        invalidate();
    }

    public void invalidate() {
        overrides.set(null);
    }

    private void changed(SettingKey key) {
        invalidate();
        events.publishEvent(new PortalChangedEvent("setting:" + key.key()));
    }

    private Map<String, Object> overrides() {
        Map<String, Object> current = overrides.get();
        if (current == null) {
            Map<String, Object> loaded = new HashMap<>();
            for (SystemSetting row : repository.findAll()) {
                if (row.getValue() != null) {
                    loaded.put(row.getSettingKey(), row.getValue());
                }
            }
            current = Map.copyOf(loaded);
            overrides.compareAndSet(null, current);
        }
        return current;
    }
}
