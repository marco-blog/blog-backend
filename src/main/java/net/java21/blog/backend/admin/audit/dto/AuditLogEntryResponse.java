package net.java21.blog.backend.admin.audit.dto;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 작업 기록 한 건(006 contracts/api.md {@code AuditLogEntry}). 변경 전후 값은 저장한 JSON 그대로(바뀐 필드만, 개인정보 평문 없음).
 * 목록에는 요청 IP가 없다.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditLogEntryResponse(long id, AdminName admin, String action, String targetType, Long targetId,
        String targetKey, Map<String, Object> before, Map<String, Object> after, String reason, Instant createdAt) {

    /** 작업한 관리자. */
    public record AdminName(long userId, String nickname) {
    }
}
