package net.java21.blog.backend.admin.report;

import net.java21.blog.backend.admin.dashboard.PendingReportCounter;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대시보드 "처리 대기 신고" 수(006 FR-103, T039): 005 {@link ReportQueryRepository#countPendingGroups()}를 그대로 쓴다. 콘솔 메뉴
 * 배지({@code GET /admin/reports/summary})와 같은 값(대상 묶음 수)이라 두 화면의 수가 어긋나지 않는다. 쿼리 1회.
 */
@Component
public class ReportPendingCounter implements PendingReportCounter {

    private final ReportQueryRepository queryRepository;

    public ReportPendingCounter(ReportQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Long countPending() {
        return queryRepository.countPendingGroups();
    }
}
