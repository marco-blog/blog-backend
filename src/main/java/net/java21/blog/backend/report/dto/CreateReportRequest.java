package net.java21.blog.backend.report.dto;

/** 회원 신고 {@code { targetType, targetId, reason, detail? }}(005 contracts/api.md). 종류·사유는 서비스가 검증한다. */
public record CreateReportRequest(String targetType, Long targetId, String reason, String detail) {
}
