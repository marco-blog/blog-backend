package net.java21.blog.backend.report.domain;

/** 신고 상태(reports.status). 처리하면 같은 대상의 대기 신고가 모두 같은 결과로 닫힌다(005 research M3). */
public enum ReportStatus {
    PENDING,
    ACTIONED,
    DISMISSED
}
