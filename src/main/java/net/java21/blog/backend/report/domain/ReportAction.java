package net.java21.blog.backend.report.domain;

/**
 * 조치(reports.action, ACTIONED일 때). 1.0에서 쓰는 값은 {@link #HIDE_CONTENT}·{@link #SUSPEND_USER}이고,
 * {@link #REMOVE_FROM_PORTAL}·{@link #BLOCK_EXTERNAL_BLOG}는 007이 외부 글·외부 블로그 처리기와 함께 쓴다(결정 표 5번).
 */
public enum ReportAction {
    HIDE_CONTENT,
    REMOVE_FROM_PORTAL,
    BLOCK_EXTERNAL_BLOG,
    SUSPEND_USER
}
