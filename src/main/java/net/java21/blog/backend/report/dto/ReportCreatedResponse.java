package net.java21.blog.backend.report.dto;

import net.java21.blog.backend.report.domain.ReportStatus;

/** 회원 신고 접수 결과 {@code { id, status: "PENDING" }}. */
public record ReportCreatedResponse(Long id, ReportStatus status) {
}
