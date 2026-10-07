package net.java21.blog.backend.admin.report.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportChannel;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.dto.ReportTargetPreview;

/**
 * 신고 상세(005 contracts/api.md {@code ReportDetail}). {@code contactEmail}은 권리 침해만(파기 후 null). {@code reports}는 같은 대상의
 * 모든 신고(최신순 100건), 대상 미정이면 이 신고 하나.
 */
public record ReportDetailResponse(Long id, ReportChannel channel, ReportStatus status, ReportAction action,
        String resolutionNote, Person handledBy, Instant handledAt, String targetUrl, String rightsBasis,
        String contactEmail, ReportTargetPreview target, List<ReportItem> reports, Long targetUserReportCount) {

    /** 회원 {@code { id, nickname }}. */
    public record Person(Long id, String nickname) {
    }

    /** 같은 대상의 신고 한 건. 비회원(권리 침해) 신고면 {@code reporter} null. */
    public record ReportItem(Long id, ReportChannel channel, Person reporter, ReportReason reason, String detail,
            Instant createdAt) {
    }
}
