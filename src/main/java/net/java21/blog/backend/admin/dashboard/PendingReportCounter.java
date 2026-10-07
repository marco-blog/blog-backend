package net.java21.blog.backend.admin.dashboard;

/**
 * 콘솔 대시보드의 "처리 대기 신고" 수(006 FR-103, research A3). 구현은 005 신고의
 * {@link net.java21.blog.backend.admin.report.ReportPendingCounter}(006 T039)이며 캐시하지 않고 매번 부른다.
 * 응답 칸 {@code pendingReports}는 계약상 null을 허용한다(신고 기능이 없는 구성에서 화면이 카드를 숨김).
 */
public interface PendingReportCounter {

    /** 처리 대기 신고 수. 신고 기능이 없으면 null. */
    Long countPending();
}
