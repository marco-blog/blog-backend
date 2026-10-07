package net.java21.blog.backend.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;

/**
 * 업무 규칙 위반을 알리는 예외. 컨트롤러·서비스는 이 예외만 던지고, 응답은 {@link GlobalExceptionHandler}가 만든다.
 * {@code message}는 영어 디버그용 설명이며 화면에 보여주지 않는다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldError> fieldErrors;
    /** 429 응답의 {@code Retry-After}(초). 없으면 null. */
    private final Long retryAfterSeconds;
    /** 오류 문구에 필요한 값(응답 {@code header.params}). 없으면 빈 맵. */
    private final Map<String, Object> params;

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public BusinessException(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        this(errorCode, message, fieldErrors, null, Map.of());
    }

    private BusinessException(ErrorCode errorCode, String message, List<FieldError> fieldErrors,
            Long retryAfterSeconds, Map<String, Object> params) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.retryAfterSeconds = retryAfterSeconds;
        // null 값은 빼고 넣은 순서를 지킨다(응답 JSON 순서).
        Map<String, Object> copy = new LinkedHashMap<>();
        if (params != null) {
            params.forEach((k, v) -> {
                if (v != null) {
                    copy.put(k, v);
                }
            });
        }
        this.params = Collections.unmodifiableMap(copy);
    }

    /** 문구에 필요한 값을 함께 알리는 오류(007: {@code EXTERNAL_BLOG_LIMIT_EXCEEDED}의 {@code max} 등). null 값은 뺀다. */
    public static BusinessException withParams(ErrorCode errorCode, String message, Map<String, ?> params) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (params != null) {
            copy.putAll(params);
        }
        return new BusinessException(errorCode, message, List.of(), null, copy);
    }

    /**
     * 다시 시도할 수 있을 때까지 기다릴 시간을 함께 알리는 오류(004: 비회원 쓰기 속도 429 {@code TOO_MANY_REQUESTS},
     * 비밀번호 시도 제한 429 {@code PASSWORD_ATTEMPTS_EXCEEDED}). 응답에 {@code Retry-After} 헤더(초, 최소 1)가 붙는다.
     */
    public static BusinessException retryAfter(ErrorCode errorCode, String message, long seconds) {
        return new BusinessException(errorCode, message, List.of(), Math.max(1, seconds), Map.of());
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }

    /** 응답 {@code header.params}. 없으면 빈 맵. */
    public Map<String, Object> params() {
        return params;
    }

    /** {@code Retry-After} 초. 없으면 null. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
