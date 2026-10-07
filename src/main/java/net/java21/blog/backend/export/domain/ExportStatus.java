package net.java21.blog.backend.export.domain;

/** 블로그 백업 상태(004 data-model blog_exports): 대기 → 만드는 중 → 완료(7일 뒤 만료) 또는 실패. */
public enum ExportStatus {
    PENDING,
    RUNNING,
    READY,
    FAILED,
    EXPIRED
}
