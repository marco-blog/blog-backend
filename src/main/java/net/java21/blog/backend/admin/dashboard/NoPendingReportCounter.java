package net.java21.blog.backend.admin.dashboard;

/** 신고 기능(005)이 없을 때의 {@link PendingReportCounter}: 늘 null(카드 없음). {@link AdminDashboardConfig}가 다른 구현이 없을 때만 등록한다. */
public class NoPendingReportCounter implements PendingReportCounter {

    @Override
    public Long countPending() {
        return null;
    }
}
