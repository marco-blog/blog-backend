package net.java21.blog.backend.admin.report.dto;

/** 신고 처리 {@code { decision: ACTION | DISMISS, action?, note?, suspendReason? }}(005 contracts/api.md). */
public record ResolveReportRequest(String decision, String action, String note, String suspendReason) {
}
