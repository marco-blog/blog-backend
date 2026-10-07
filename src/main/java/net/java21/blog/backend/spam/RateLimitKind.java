package net.java21.blog.backend.spam;

/** 속도 제한 종류(005 research M8). 같은 주체라도 종류가 다르면 따로 센다. */
public enum RateLimitKind {
    POST_PUBLISH,
    COMMENT,
    GUESTBOOK,
    MEDIA_UPLOAD,
    SIGNUP,
    REPORT,
    RIGHTS_REQUEST,
    TRACKBACK_RECEIVE,
    LOGIN_FAILURE,
    DUPLICATE_CONTENT,
    // 007 외부 블로그 미리보기·소유 인증 확인(회원별 시간당)
    EXTERNAL_PREVIEW,
    EXTERNAL_VERIFY_CHECK
}
