package net.java21.blog.backend.admin.report.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;

/** 신고 묶음(005 contracts/api.md {@code ReportGroup}). {@code channel}은 MEMBER·RIGHTS_REQUEST·MIXED. */
public record ReportGroupResponse(Long representativeId, ReportTargetType targetType, Long targetId, String channel,
        long reportCount, List<ReasonCount> reasons, Instant firstReportedAt, Instant lastReportedAt,
        ReportStatus status, ReportAction action, ReportTargetPreview target) {

    public record ReasonCount(ReportReason reason, long count) {
    }
}
