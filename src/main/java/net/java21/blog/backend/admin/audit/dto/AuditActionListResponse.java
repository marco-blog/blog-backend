package net.java21.blog.backend.admin.audit.dto;

import java.util.List;

/** {@code GET /admin/audit-logs/actions}: 알려진 작업 종류와 대상 종류(작업 기록 화면의 필터 선택지). */
public record AuditActionListResponse(List<String> actions, List<String> targetTypes) {
}
