package net.java21.blog.backend.common.api;

import java.util.Map;

/**
 * 입력 검증 오류 한 건. {@code code}는 front 메시지 키({@code fieldErrors.{code}})가 된다.
 * 길이 제한처럼 문구에 필요한 값은 {@code params}에 넣는다(예: {@code {"max": 200}}).
 */
public record FieldError(String field, String code, Map<String, Object> params) {

    public FieldError {
        params = params == null ? Map.of() : Map.copyOf(params);
    }

    public static FieldError of(String field, String code) {
        return new FieldError(field, code, Map.of());
    }
}
