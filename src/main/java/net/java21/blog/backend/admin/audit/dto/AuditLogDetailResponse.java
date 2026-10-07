package net.java21.blog.backend.admin.audit.dto;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code GET /admin/audit-logs/{id}}: {@link AuditLogEntryResponse}에 요청 IP를 더한다. 요청 IP는 요청자가 지금(DB 기준) 최고 관리자일
 * 때만 복호화 값이고 일반 관리자에게는 null이다(research A6, 결정 표 18번).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditLogDetailResponse(long id, AuditLogEntryResponse.AdminName admin, String action, String targetType,
        Long targetId, String targetKey, Map<String, Object> before, Map<String, Object> after, String reason,
        Instant createdAt, String requestIp) {
}
