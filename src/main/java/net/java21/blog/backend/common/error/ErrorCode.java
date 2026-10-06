package net.java21.blog.backend.common.error;

import org.springframework.http.HttpStatus;

/**
 * 공통 오류 코드. 값(name)은 응답의 {@code header.resultCode}이며 한번 정하면 바꾸지 않는다(FR-154).
 * 기능별 코드(POST_NOT_FOUND 등)는 각 기능을 만들 때 contracts/api.md 표에 맞춰 더한다.
 */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    ORIGIN_NOT_ALLOWED(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
