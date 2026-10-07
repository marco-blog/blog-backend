package net.java21.blog.backend.admin.report.dto;

import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportStatus;

/** 처리 결과 {@code { resolvedCount, decision, action }}. {@code decision}은 닫은 상태(ACTIONED·DISMISSED). */
public record ResolveReportResponse(int resolvedCount, ReportStatus decision, ReportAction action) {
}
