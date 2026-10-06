package net.java21.blog.backend.setting;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.service.ScoreWeights;

/**
 * 운영 설정 키(003 research P3, contracts/api.md "관리자: 운영 설정"). 키마다 값 형식 검증과 기본값(프로퍼티)을 정한다.
 * 005·007이 키를 더한다. 값은 JSON으로 저장할 수 있는 모양(객체 {@code Map}, 문자열, 정수)으로 다룬다.
 */
public enum SettingKey {

    /** 인기 점수 가중치 객체. 가중치 4개 0~1000, {@code halfLifeHours} 1~720, {@code reportPenalty} 0~1, 모두 필수. */
    PORTAL_SCORE_WEIGHTS("portal.score-weights") {
        @Override
        public Object defaultValue(PortalProperties properties) {
            return properties.scoreWeights().toJson();
        }

        @Override
        public Object normalize(Object raw) {
            if (!(raw instanceof Map<?, ?> json)) {
                throw invalid(new FieldError(VALUE, INVALID, Map.of()));
            }
            List<FieldError> errors = new ArrayList<>();
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (String field : ScoreWeights.FIELDS) {
                double[] range = switch (field) {
                    case "halfLifeHours" -> new double[] {1, 720};
                    case "reportPenalty" -> new double[] {0, 1};
                    default -> new double[] {0, 1000};
                };
                Object value = json.get(field);
                if (value == null) {
                    errors.add(FieldError.of(VALUE + "." + field, "REQUIRED"));
                } else if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())
                        || n.doubleValue() < range[0] || n.doubleValue() > range[1]) {
                    errors.add(new FieldError(VALUE + "." + field, INVALID, range(range[0], range[1])));
                } else {
                    normalized.put(field, n);
                }
            }
            if (!errors.isEmpty()) {
                throw invalid(errors);
            }
            return ScoreWeights.fromJson(normalized).toJson();
        }
    },

    /** 가입 후 포털 노출까지 대기. ISO-8601 기간 문자열 {@code PT0S}~{@code P30D}. */
    PORTAL_NEW_MEMBER_DELAY("portal.new-member-delay") {
        @Override
        public Object defaultValue(PortalProperties properties) {
            return properties.newMemberDelay().toString();
        }

        @Override
        public Object normalize(Object raw) {
            Map<String, Object> params = Map.of("min", "PT0S", "max", "P30D");
            if (!(raw instanceof String text)) {
                throw invalid(new FieldError(VALUE, INVALID, params));
            }
            Duration duration;
            try {
                duration = Duration.parse(text);
            } catch (DateTimeParseException e) {
                throw invalid(new FieldError(VALUE, INVALID, params));
            }
            if (duration.isNegative() || duration.compareTo(Duration.ofDays(30)) > 0) {
                throw invalid(new FieldError(VALUE, INVALID, params));
            }
            return duration.toString();
        }
    },

    /** 포털 노출 최소 본문 길이(문자 수). 정수 0~10000. */
    PORTAL_MIN_CONTENT_LENGTH("portal.min-content-length") {
        @Override
        public Object defaultValue(PortalProperties properties) {
            return properties.minContentLength();
        }

        @Override
        public Object normalize(Object raw) {
            return integer(raw, 0, 10_000);
        }
    },

    /** 주제 자동 숨김 기준(최근 30일 글 수). 정수 0~1000. */
    PORTAL_TOPIC_AUTO_HIDE_THRESHOLD("portal.topic-auto-hide-threshold") {
        @Override
        public Object defaultValue(PortalProperties properties) {
            return properties.topicAutoHideThreshold();
        }

        @Override
        public Object normalize(Object raw) {
            return integer(raw, 0, 1000);
        }
    };

    static final String VALUE = "value";
    static final String INVALID = "INVALID";

    private final String key;

    SettingKey(String key) {
        this.key = key;
    }

    /** 키 이름(예: {@code portal.min-content-length}). */
    public String key() {
        return key;
    }

    /** 행이 없을 때 쓰는 값(프로퍼티). */
    public abstract Object defaultValue(PortalProperties properties);

    /**
     * 관리자가 보낸 값을 검증하고 저장할 모양으로 바꾼다.
     *
     * @throws BusinessException 400 {@code VALIDATION_FAILED}(field {@code value} 또는 {@code value.like} 등, {@code INVALID} + 범위)
     */
    public abstract Object normalize(Object raw);

    public static Optional<SettingKey> find(String key) {
        return Arrays.stream(values()).filter(k -> k.key.equals(key)).findFirst();
    }

    /** 모르는 키는 404 {@code SETTING_NOT_FOUND}. */
    public static SettingKey require(String key) {
        return find(key).orElseThrow(() -> new BusinessException(ErrorCode.SETTING_NOT_FOUND, "Unknown setting: " + key));
    }

    private static Object integer(Object raw, int min, int max) {
        if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                && n.doubleValue() >= min && n.doubleValue() <= max) {
            return n.intValue();
        }
        throw invalid(new FieldError(VALUE, INVALID, range(min, max)));
    }

    private static Map<String, Object> range(double min, double max) {
        return Map.of("min", plain(min), "max", plain(max));
    }

    private static Number plain(double value) {
        return value == Math.rint(value) ? (Number) (int) value : (Number) value;
    }

    private static BusinessException invalid(FieldError error) {
        return invalid(List.of(error));
    }

    private static BusinessException invalid(List<FieldError> errors) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid setting value", errors);
    }
}
