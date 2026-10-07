package net.java21.blog.backend.report.domain;

/**
 * 신고 대상 종류(reports.target_type, 다형 참조). {@code EXTERNAL_POST}·{@code EXTERNAL_BLOG}(007)는 처리기가 등록되기 전까지
 * 신고를 받지 않는다(400 field {@code targetType} {@code INVALID}, 결정 표 5번).
 */
public enum ReportTargetType {
    POST,
    COMMENT,
    GUESTBOOK,
    TRACKBACK,
    EXTERNAL_POST,
    EXTERNAL_BLOG
}
