package net.java21.blog.backend.admin.report.dto;

/** 콘솔 메뉴 배지 {@code { pendingCount }}: 대기 중인 신고 묶음 수. */
public record ReportSummaryResponse(long pendingCount) {
}
