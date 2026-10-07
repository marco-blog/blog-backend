package net.java21.blog.backend.trackback.domain;

/** 보낸 트랙백 실패 이유(trackback_ping_logs.error_code, 005 data-model). */
public enum PingErrorCode {
    INVALID_URL,
    BLOCKED_ADDRESS,
    TIMEOUT,
    HTTP_ERROR,
    REMOTE_ERROR
}
