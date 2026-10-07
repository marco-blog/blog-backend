package net.java21.blog.backend.report.dto;

import net.java21.blog.backend.report.domain.ReportTargetType;

/** 신고 대상 키(종류 + id). */
public record TargetKey(ReportTargetType type, Long id) {
}
