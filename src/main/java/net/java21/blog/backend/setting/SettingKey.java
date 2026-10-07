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
import net.java21.blog.backend.portal.service.ScoreWeights;

/**
 * 운영 설정 키(003 research P3, contracts/api.md "관리자: 운영 설정"). 키마다 값 형식 검증과 기본값(프로퍼티)을 정한다.
 * 005·007이 키를 더한다. 값은 JSON으로 저장할 수 있는 모양(객체 {@code Map}, 문자열, 정수)으로 다룬다.
 */
public enum SettingKey {

    /** 인기 점수 가중치 객체. 가중치 4개 0~1000, {@code halfLifeHours} 1~720, {@code reportPenalty} 0~1, 모두 필수. */
    PORTAL_SCORE_WEIGHTS("portal.score-weights") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.portal().scoreWeights().toJson();
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
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.portal().newMemberDelay().toString();
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
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.portal().minContentLength();
        }

        @Override
        public Object normalize(Object raw) {
            return integer(raw, 0, 10_000);
        }
    },

    /** 주제 자동 숨김 기준(최근 30일 글 수). 정수 0~1000. */
    PORTAL_TOPIC_AUTO_HIDE_THRESHOLD("portal.topic-auto-hide-threshold") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.portal().topicAutoHideThreshold();
        }

        @Override
        public Object normalize(Object raw) {
            return integer(raw, 0, 1000);
        }
    },

    /** 회원의 처음 발행·예약 1시간 한도(005 FR-142). 정수 1~10000. */
    RATELIMIT_POST_PUBLISH_PER_HOUR("ratelimit.post-publish-per-hour") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.rateLimit().postPublishPerHour();
        }

        @Override
        public Object normalize(Object raw) {
            return integer(raw, 1, 10_000);
        }
    },

    /** 회원 ID 또는 비회원 IP의 댓글 1분 한도. 정수 1~10000. */
    RATELIMIT_COMMENT_PER_MINUTE("ratelimit.comment-per-minute") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.rateLimit().commentPerMinute();
        }

        @Override
        public Object normalize(Object raw) {
            return integer(raw, 1, 10_000);
        }
    },

    /** 회원 ID 또는 비회원 IP의 방명록 1분 한도. 정수 1~10000. */
    RATELIMIT_GUESTBOOK_PER_MINUTE("ratelimit.guestbook-per-minute") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.rateLimit().guestbookPerMinute();
        }

        @Override
        public Object normalize(Object raw) {
            return integer(raw, 1, 10_000);
        }
    },

    /** 회원의 이미지 업로드 1분 한도. 정수 1~10000. */
    RATELIMIT_MEDIA_UPLOAD_PER_MINUTE("ratelimit.media-upload-per-minute") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.rateLimit().mediaUploadPerMinute();
        }

        @Override
        public Object normalize(Object raw) {
            return integer(raw, 1, 10_000);
        }
    },

    /** 같은 IP의 가입 1시간 한도. 정수 1~100000. */
    RATELIMIT_SIGNUP_PER_IP_PER_HOUR("ratelimit.signup-per-ip-per-hour") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.rateLimit().signupPerIpPerHour();
        }

        @Override
        public Object normalize(Object raw) {
            return integer(raw, 1, 100_000);
        }
    },

    /** 반복 스팸 기준 {@code { windowMinutes: 1~1440, maxCount: 2~100 }}, 둘 다 필수(005 FR-144). */
    SPAM_DUPLICATE_COMMENT("spam.duplicate-comment") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("windowMinutes", defaults.spam().duplicateComment().windowMinutes());
            value.put("maxCount", defaults.spam().duplicateComment().maxCount());
            return value;
        }

        @Override
        public Object normalize(Object raw) {
            if (!(raw instanceof Map<?, ?> json)) {
                throw invalid(new FieldError(VALUE, INVALID, Map.of()));
            }
            List<FieldError> errors = new ArrayList<>();
            Map<String, Object> normalized = new LinkedHashMap<>();
            String[] fields = {"windowMinutes", "maxCount"};
            int[][] ranges = {{1, 1440}, {2, 100}};
            for (int i = 0; i < fields.length; i++) {
                Object value = json.get(fields[i]);
                if (value == null) {
                    errors.add(FieldError.of(VALUE + "." + fields[i], "REQUIRED"));
                } else if (value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())
                        && n.doubleValue() >= ranges[i][0] && n.doubleValue() <= ranges[i][1]) {
                    normalized.put(fields[i], n.intValue());
                } else {
                    errors.add(new FieldError(VALUE + "." + fields[i], INVALID, range(ranges[i][0], ranges[i][1])));
                }
            }
            if (!errors.isEmpty()) {
                throw invalid(errors);
            }
            return normalized;
        }
    },

    /** 외부 블로그 수집 주기(007 FR-113). ISO-8601 기간 문자열 {@code PT10M}~{@code PT24H}. 프로퍼티 기본값은 시험용으로 더 짧을 수 있다. */
    EXTERNAL_FETCH_INTERVAL("external.fetch-interval") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.external().fetchInterval().toString();
        }

        @Override
        public Object normalize(Object raw) {
            Map<String, Object> params = Map.of("min", "PT10M", "max", "PT24H");
            if (!(raw instanceof String text)) {
                throw invalid(new FieldError(VALUE, INVALID, params));
            }
            Duration duration;
            try {
                duration = Duration.parse(text.strip());
            } catch (DateTimeParseException e) {
                throw invalid(new FieldError(VALUE, INVALID, params));
            }
            if (duration.compareTo(Duration.ofMinutes(10)) < 0 || duration.compareTo(Duration.ofHours(24)) > 0) {
                throw invalid(new FieldError(VALUE, INVALID, params));
            }
            return duration.toString();
        }
    },

    /** 자동 분류 채택 기준(007 FR-118·119). 숫자 0~1. */
    EXTERNAL_AUTO_CLASSIFY_MIN_CONFIDENCE("external.auto-classify-min-confidence") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.external().autoClassifyMinConfidence();
        }

        @Override
        public Object normalize(Object raw) {
            return decimal(raw, 0, 1);
        }
    },

    /** 외부 글 인기 점수 가중치(007 FR-124). 숫자 0~10. 바꾸면 포털 캐시를 비운다. */
    EXTERNAL_SCORE_WEIGHT("external.score-weight") {
        @Override
        public Object defaultValue(SettingDefaults defaults) {
            return defaults.external().scoreWeight();
        }

        @Override
        public Object normalize(Object raw) {
            return decimal(raw, 0, 10);
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
    public abstract Object defaultValue(SettingDefaults defaults);

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

    private static Object decimal(Object raw, double min, double max) {
        if (raw instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() >= min
                && n.doubleValue() <= max) {
            return n.doubleValue();
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
