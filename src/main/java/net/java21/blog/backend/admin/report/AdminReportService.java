package net.java21.blog.backend.admin.report;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.report.dto.AssignTargetRequest;
import net.java21.blog.backend.admin.report.dto.ReportDetailResponse;
import net.java21.blog.backend.admin.report.dto.ReportDetailResponse.Person;
import net.java21.blog.backend.admin.report.dto.ReportDetailResponse.ReportItem;
import net.java21.blog.backend.admin.report.dto.ReportGroupResponse;
import net.java21.blog.backend.admin.report.dto.ReportGroupResponse.ReasonCount;
import net.java21.blog.backend.admin.report.dto.ReportSummaryResponse;
import net.java21.blog.backend.admin.report.dto.ResolveReportRequest;
import net.java21.blog.backend.admin.report.dto.ResolveReportResponse;
import net.java21.blog.backend.admin.user.SuspensionService;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.moderation.ContentHideService;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportChannel;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.dto.TargetKey;
import net.java21.blog.backend.report.event.ReportResolvedEvent;
import net.java21.blog.backend.report.repository.ReportRepository;
import net.java21.blog.backend.report.repository.ReportTargetPreviewRepository;
import net.java21.blog.backend.report.service.ReportTarget;
import net.java21.blog.backend.report.service.ReportTargetHandlers;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 신고 처리(005 FR-041, research M3, 006 FR-106).
 * <ul>
 *   <li>목록: 대상 묶음(대기는 첫 접수 오래된 순)과 대상 미리보기. 쿼리는 묶음 2회 + 대상 종류별 미리보기 1회씩.</li>
 *   <li>처리: 대상의 모든 PENDING 신고를 같은 결과로 조건부 UPDATE 한 번에 닫는다. {@code HIDE_CONTENT}는 {@link ContentHideService},
 *       {@code SUSPEND_USER}는 {@link SuspensionService}를 같은 트랜잭션에서 부른다. 작업 기록 {@code REPORT_ACTION}·
 *       {@code REPORT_DISMISS}(target REPORT/대표 id, after에 닫은 id 목록·조치). 커밋 뒤 {@link ReportResolvedEvent}로 회원 신고자
 *       알림과 권리 침해 결과 메일을 보낸다.</li>
 *   <li>대상 지정: 대상이 없는 권리 침해 신고만(아니면 409 {@code REPORT_ALREADY_TARGETED}).</li>
 * </ul>
 */
@Service
public class AdminReportService {

    private final ReportRepository reportRepository;
    private final ReportQueryRepository queryRepository;
    private final ReportTargetPreviewRepository previews;
    private final ReportTargetHandlers handlers;
    private final ContentHideService contentHideService;
    private final SuspensionService suspensionService;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AdminReportService(ReportRepository reportRepository, ReportQueryRepository queryRepository,
            ReportTargetPreviewRepository previews, ReportTargetHandlers handlers,
            ContentHideService contentHideService, SuspensionService suspensionService, UserRepository userRepository,
            AdminAuditService auditService, ApplicationEventPublisher events, Clock clock) {
        this.reportRepository = reportRepository;
        this.queryRepository = queryRepository;
        this.previews = previews;
        this.handlers = handlers;
        this.contentHideService = contentHideService;
        this.suspensionService = suspensionService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<ReportGroupResponse> list(String status, String targetType, String channel, Pageable pageable) {
        ReportQueryRepository.Filter filter = new ReportQueryRepository.Filter(
                status == null || status.isBlank() ? ReportStatus.PENDING : parse(ReportStatus.class, status, "status"),
                targetType == null || targetType.isBlank() ? null
                        : parse(ReportTargetType.class, targetType, "targetType"),
                channel == null || channel.isBlank() ? null : parse(ReportChannel.class, channel, "channel"));
        Page<ReportGroupRow> groups = queryRepository.findGroups(filter, pageable);
        List<TargetKey> keys = groups.getContent().stream().filter(g -> g.targetType() != null)
                .map(g -> new TargetKey(g.targetType(), g.targetId())).toList();
        Map<TargetKey, ReportTargetPreview> found = previews.previews(keys);
        List<ReportGroupResponse> content = new ArrayList<>();
        for (ReportGroupRow group : groups.getContent()) {
            List<ReasonCount> reasons = group.reasons().entrySet().stream()
                    .map(e -> new ReasonCount(e.getKey(), e.getValue())).toList();
            String groupChannel = group.minChannel() == group.maxChannel() ? group.minChannel().name() : "MIXED";
            ReportTargetPreview target = group.targetType() == null ? null
                    : found.get(new TargetKey(group.targetType(), group.targetId()));
            content.add(new ReportGroupResponse(group.representativeId(), group.targetType(), group.targetId(),
                    groupChannel, group.reportCount(), reasons, group.firstReportedAt(), group.lastReportedAt(),
                    filter.status(), group.action(), target));
        }
        return new PageImpl<>(content, pageable, groups.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ReportSummaryResponse summary() {
        return new ReportSummaryResponse(queryRepository.countPendingGroups());
    }

    @Transactional(readOnly = true)
    public ReportDetailResponse detail(long id) {
        Report report = requireReport(id);
        List<Report> same = report.hasTarget()
                ? queryRepository.findSameTarget(report.getTargetType(), report.getTargetId())
                : List.of(report);
        ReportTargetPreview target = report.hasTarget()
                ? previews.preview(report.getTargetType(), report.getTargetId())
                : null;
        Long targetUserReportCount = report.getTargetUser() == null ? null
                : queryRepository.countReceivedBy(report.getTargetUser().getId());
        List<ReportItem> items = same.stream().map(r -> new ReportItem(r.getId(), r.getChannel(),
                person(r.getReporter()), r.getReason(), r.getDetail(), r.getCreatedAt())).toList();
        return new ReportDetailResponse(report.getId(), report.getChannel(), report.getStatus(), report.getAction(),
                report.getResolutionNote(), person(report.getHandledBy()), report.getHandledAt(),
                report.getTargetUrl(), report.getRightsBasis(),
                report.getChannel() == ReportChannel.RIGHTS_REQUEST ? report.getContactEmail() : null, target, items,
                targetUserReportCount);
    }

    /** 대상 미정 권리 침해 신고에 대상을 정한다. 이미 대상이 있으면 409, 없는 대상 404 {@code CONTENT_NOT_FOUND}. */
    @Transactional
    public ReportDetailResponse assignTarget(long adminId, long id, AssignTargetRequest request) {
        Report report = requireReport(id);
        if (report.hasTarget()) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_TARGETED, "Report already has a target: " + id);
        }
        if (request.targetId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of("targetId", "REQUIRED")));
        }
        ReportTarget target = handlers.require(request.targetType(), "targetType").resolveForAdmin(request.targetId());
        report.assignTarget(target.type(), target.id(), target.targetUser(), target.targetBlog());
        reportRepository.flush();
        return detail(id);
    }

    @Transactional
    public ResolveReportResponse resolve(long adminId, long id, ResolveReportRequest request, String requestIp) {
        Report report = requireReport(id);
        boolean action = parseDecision(request.decision());
        if (!report.isPending()) {
            throw new BusinessException(ErrorCode.REPORT_ALREADY_RESOLVED, "Report already resolved: " + id);
        }
        String note = SuspensionService.reason(request.note(), false, "note");
        ReportAction chosen = null;
        if (action) {
            if (!report.hasTarget()) {
                throw new BusinessException(ErrorCode.REPORT_TARGET_REQUIRED, "Report has no target: " + id);
            }
            chosen = parseAction(request.action());
            if (chosen == ReportAction.HIDE_CONTENT) {
                contentHideService.hide(adminId, report.getTargetType(), report.getTargetId(),
                        note == null ? "report #" + id : note, requestIp);
            } else if (chosen == ReportAction.SUSPEND_USER) {
                if (report.getTargetUser() == null) {
                    throw new BusinessException(ErrorCode.REPORT_ACTION_NOT_ALLOWED,
                            "Report target has no member author: " + id);
                }
                suspensionService.suspend(adminId, report.getTargetUser().getId(), request.suspendReason(),
                        requestIp);
            } else {
                throw new BusinessException(ErrorCode.REPORT_ACTION_NOT_ALLOWED, "Action not available: " + chosen);
            }
        }
        List<Long> ids = report.hasTarget()
                ? reportRepository.findIdsByTargetAndStatus(report.getTargetType(), report.getTargetId(),
                        ReportStatus.PENDING)
                : List.of(report.getId());
        List<Report> closing = reportRepository.findAllById(ids);
        List<ReportResolvedEvent.MemberRecipient> members = new ArrayList<>();
        List<ReportResolvedEvent.RightsRecipient> rights = new ArrayList<>();
        Map<Long, Boolean> notified = new HashMap<>();
        for (Report r : closing) {
            if (r.getReporter() != null && notified.putIfAbsent(r.getReporter().getId(), Boolean.TRUE) == null) {
                members.add(new ReportResolvedEvent.MemberRecipient(r.getId(), r.getReporter().getId()));
            } else if (r.getChannel() == ReportChannel.RIGHTS_REQUEST && r.getContactEmail() != null) {
                rights.add(new ReportResolvedEvent.RightsRecipient(r.getId(), r.getContactEmail(), r.getTargetUrl()));
            }
        }
        ReportStatus decision = action ? ReportStatus.ACTIONED : ReportStatus.DISMISSED;
        int resolved = reportRepository.resolvePending(ids, decision, chosen, note,
                userRepository.getReferenceById(adminId), clock.instant());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", decision.name());
        after.put("action", chosen == null ? null : chosen.name());
        after.put("reportIds", ids.stream().sorted().toList());
        auditService.record(adminId, action ? AuditActions.REPORT_ACTION : AuditActions.REPORT_DISMISS,
                AuditActions.TARGET_REPORT, id, null, Map.of("status", ReportStatus.PENDING.name()), after, note,
                requestIp);
        events.publishEvent(new ReportResolvedEvent(report.getTargetType(), decision, members, rights));
        return new ResolveReportResponse(resolved, decision, chosen);
    }

    private Report requireReport(long id) {
        Report report = queryRepository.findWithPeople(id);
        if (report == null) {
            throw new BusinessException(ErrorCode.REPORT_NOT_FOUND, "Report not found: " + id);
        }
        return report;
    }

    private static boolean parseDecision(String raw) {
        if ("ACTION".equals(raw)) {
            return true;
        }
        if ("DISMISS".equals(raw)) {
            return false;
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(raw == null
                || raw.isBlank() ? FieldError.of("decision", "REQUIRED")
                : new FieldError("decision", "INVALID", Map.of("allowed", List.of("ACTION", "DISMISS")))));
    }

    private static ReportAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of("action", "REQUIRED")));
        }
        try {
            return ReportAction.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(new FieldError(
                    "action", "INVALID", Map.of("allowed", List.of("HIDE_CONTENT", "SUSPEND_USER")))));
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of(field, "INVALID")));
        }
    }

    private static Person person(User user) {
        return user == null ? null : new Person(user.getId(), user.getNickname());
    }
}
