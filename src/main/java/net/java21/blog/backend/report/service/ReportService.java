package net.java21.blog.backend.report.service;

import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.text.PlainTextNormalizer;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.dto.CreateReportRequest;
import net.java21.blog.backend.report.dto.ReportCreatedResponse;
import net.java21.blog.backend.report.repository.ReportRepository;
import net.java21.blog.backend.spam.RateLimitKind;
import net.java21.blog.backend.spam.RateLimitPolicy;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 신고 접수(005 FR-040, research M2). 처리기가 대상을 신고자 기준으로 읽고(볼 수 없으면 404, 자기 콘텐츠 422), 같은 대상은 한 번만
 * (409, 처리 여부와 무관 — 저장 전 조회와 UNIQUE 위반 모두), 회원당 1시간 {@code blog.reports.member-per-hour}건(429). 접수 시점의 대상
 * 작성 회원·블로그를 함께 저장한다. 신고자 신원은 대상 작성자에게 어떤 응답에도 나오지 않는다.
 */
@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final ReportTargetHandlers handlers;
    private final UserRepository userRepository;
    private final RateLimitPolicy rateLimits;

    public ReportService(ReportRepository reportRepository, ReportTargetHandlers handlers,
            UserRepository userRepository, RateLimitPolicy rateLimits) {
        this.reportRepository = reportRepository;
        this.handlers = handlers;
        this.userRepository = userRepository;
        this.rateLimits = rateLimits;
    }

    @Transactional
    public ReportCreatedResponse create(long reporterId, CreateReportRequest request) {
        ReportTargetHandler handler = handlers.require(request.targetType(), "targetType");
        if (request.targetId() == null) {
            throw invalid(FieldError.of("targetId", "REQUIRED"));
        }
        ReportReason reason = reason(request.reason(), List.of(ReportReason.values()));
        String detail = detail(request.detail(), reason);
        User reporter = userRepository.findById(reporterId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Inactive member: " + reporterId));
        rateLimits.check(RateLimitKind.REPORT, "u:" + reporterId);
        ReportTarget target = handler.resolveForReporter(request.targetId(), reporterId);
        if (reportRepository.existsByReporterIdAndTargetTypeAndTargetId(reporterId, target.type(), target.id())) {
            throw alreadyReported(target);
        }
        Report report;
        try {
            report = reportRepository.saveAndFlush(Report.member(reporter, target.type(), target.id(),
                    target.targetUser(), target.targetBlog(), reason, detail));
        } catch (DataIntegrityViolationException e) {
            throw alreadyReported(target);
        }
        return new ReportCreatedResponse(report.getId(), report.getStatus());
    }

    /** 사유 문자열 → 값. 없으면 {@code reason REQUIRED}, 허용하지 않는 값이면 {@code reason INVALID}({@code params.allowed}). */
    static ReportReason reason(String raw, List<ReportReason> allowed) {
        if (raw == null || raw.isBlank()) {
            throw invalid(FieldError.of("reason", "REQUIRED"));
        }
        for (ReportReason value : allowed) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        throw invalid(new FieldError("reason", "INVALID", Map.of("allowed", allowed.stream().map(Enum::name)
                .toList())));
    }

    /** 설명: 앞뒤 공백 제거, 비면 null. OTHER면 필수, {@link Report#DETAIL_MAX}자 이하. */
    static String detail(String raw, ReportReason reason) {
        String detail = raw == null ? "" : PlainTextNormalizer.multiline(raw);
        if (detail.isEmpty()) {
            if (reason == ReportReason.OTHER) {
                throw invalid(FieldError.of("detail", "REQUIRED"));
            }
            return null;
        }
        if (detail.codePointCount(0, detail.length()) > Report.DETAIL_MAX) {
            throw invalid(new FieldError("detail", "TOO_LONG", Map.of("max", Report.DETAIL_MAX)));
        }
        return detail;
    }

    static BusinessException invalid(FieldError error) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(error));
    }

    private static BusinessException alreadyReported(ReportTarget target) {
        return new BusinessException(ErrorCode.REPORT_ALREADY_EXISTS,
                "Already reported: " + target.type() + " " + target.id());
    }
}
