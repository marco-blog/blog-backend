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
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    // 001 인증·블로그·글 (contracts/api.md 오류 코드 표)
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    CURRENT_PASSWORD_MISMATCH(HttpStatus.BAD_REQUEST),
    EMAIL_TAKEN(HttpStatus.CONFLICT),
    HANDLE_TAKEN(HttpStatus.CONFLICT),
    BLOG_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
    LAST_BLOG_CANNOT_BE_DELETED(HttpStatus.CONFLICT),
    POST_NOT_PUBLISHED(HttpStatus.CONFLICT),
    HANDLE_RESERVED(HttpStatus.UNPROCESSABLE_CONTENT),
    HANDLE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT),
    TERMS_VERSION_OUTDATED(HttpStatus.UNPROCESSABLE_CONTENT),
    POST_CONTENT_EMPTY(HttpStatus.UNPROCESSABLE_CONTENT),
    POST_NOT_IN_TRASH(HttpStatus.UNPROCESSABLE_CONTENT),
    CATEGORY_DEPTH_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
    TAG_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
    BLOG_NOT_FOUND(HttpStatus.NOT_FOUND),
    POST_NOT_FOUND(HttpStatus.NOT_FOUND),
    DRAFT_NOT_FOUND(HttpStatus.NOT_FOUND),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND),
    CATEGORY_NAME_TAKEN(HttpStatus.CONFLICT),

    // 001 계정 설정 (Phase 4)
    PASSWORD_RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST),

    // 001 댓글 (US3, FR-027~029)
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    REPLY_DEPTH_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT),
    COMMENTS_DISABLED(HttpStatus.UNPROCESSABLE_CONTENT),


    // 001 이미지 (US4, FR-038·039, FR-130~132, FR-156)
    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND),
    MEDIA_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE),
    MEDIA_TYPE_NOT_ALLOWED(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    MEDIA_TEMP_QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS),
    THUMBNAIL_SIZE_NOT_ALLOWED(HttpStatus.BAD_REQUEST),

    // 002 구독과 탐색 (002 contracts/api.md 오류 코드 표)
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND),
    CANNOT_SUBSCRIBE_OWN_BLOG(HttpStatus.UNPROCESSABLE_CONTENT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
