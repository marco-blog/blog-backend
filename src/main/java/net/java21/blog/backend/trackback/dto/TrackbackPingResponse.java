package net.java21.blog.backend.trackback.dto;

import java.time.Instant;

import net.java21.blog.backend.trackback.domain.PingErrorCode;
import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;

/** 보낸 트랙백 기록(005 contracts/api.md {@code TrackbackPing}). PENDING이면 "보내는 중". */
public record TrackbackPingResponse(Long id, String targetUrl, PingStatus status, PingErrorCode errorCode,
        String errorMessage, Instant attemptedAt, Instant createdAt) {

    public static TrackbackPingResponse of(TrackbackPingLog log) {
        return new TrackbackPingResponse(log.getId(), log.getTargetUrl(), log.getStatus(), log.getErrorCode(),
                log.getErrorMessage(), log.getAttemptedAt(), log.getCreatedAt());
    }
}
