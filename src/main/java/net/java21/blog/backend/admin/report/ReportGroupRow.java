package net.java21.blog.backend.admin.report;

import java.time.Instant;
import java.util.Map;

import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportChannel;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportTargetType;

/**
 * 신고 묶음 한 줄(005 research M3): 같은 대상(대상 미정 권리 침해는 신고 하나)의 신고들.
 *
 * @param representativeId 묶음에서 가장 먼저 접수된 신고 id
 * @param minChannel       묶음의 채널 최솟값(최댓값과 다르면 MIXED)
 * @param reasons          사유별 수(0인 사유는 없음)
 */
public record ReportGroupRow(Long representativeId, ReportTargetType targetType, Long targetId, long reportCount,
        Instant firstReportedAt, Instant lastReportedAt, ReportChannel minChannel, ReportChannel maxChannel,
        ReportAction action, Map<ReportReason, Long> reasons) {
}
