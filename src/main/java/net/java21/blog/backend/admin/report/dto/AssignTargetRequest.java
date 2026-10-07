package net.java21.blog.backend.admin.report.dto;

/** 대상 지정 {@code { targetType, targetId }}. */
public record AssignTargetRequest(String targetType, Long targetId) {
}
