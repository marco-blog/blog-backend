package net.java21.blog.backend.common.error;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 직접 만든 Bean Validation 제약에 붙여 검증 오류의 {@code fieldErrors[].code}를 정한다(예: {@code PASSWORD_WEAK}).
 * 붙이지 않은 제약은 {@link GlobalExceptionHandler}의 기본 규칙(REQUIRED, TOO_LONG 등)을 따른다.
 */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FieldErrorCode {

    /** 005: 이름류·본문류에 거부 금칙어가 있음(FR-143). 어느 단어인지는 {@code params}에 넣지 않는다. */
    String BANNED_WORD = "BANNED_WORD";

    String value();
}
