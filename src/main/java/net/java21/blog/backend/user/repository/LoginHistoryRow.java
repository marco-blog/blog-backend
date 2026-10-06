package net.java21.blog.backend.user.repository;

import java.time.Instant;

/**
 * 로그인 기록 한 줄(DTO projection). {@code ip}는 복호화한 원문이므로 서비스가 가린 뒤에만 응답에 쓴다.
 */
public record LoginHistoryRow(Instant at, boolean success, String ip, String userAgent) {

    @Override
    public String toString() {
        return "LoginHistoryRow[at=" + at + ", success=" + success + ", ip=****]";
    }
}
