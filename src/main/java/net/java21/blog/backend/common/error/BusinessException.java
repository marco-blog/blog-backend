package net.java21.blog.backend.common.error;

import java.util.List;

import net.java21.blog.backend.common.api.FieldError;

/**
 * 업무 규칙 위반을 알리는 예외. 컨트롤러·서비스는 이 예외만 던지고, 응답은 {@link GlobalExceptionHandler}가 만든다.
 * {@code message}는 영어 디버그용 설명이며 화면에 보여주지 않는다.
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final List<FieldError> fieldErrors;

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public BusinessException(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
        super(message);
        this.errorCode = errorCode;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public List<FieldError> fieldErrors() {
        return fieldErrors;
    }
}
