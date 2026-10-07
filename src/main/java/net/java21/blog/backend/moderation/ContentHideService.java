package net.java21.blog.backend.moderation;

import java.util.Map;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.user.SuspensionService;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.repository.ReportTargetPreviewRepository;
import net.java21.blog.backend.report.service.HideChange;
import net.java21.blog.backend.report.service.ReportTargetHandler;
import net.java21.blog.backend.report.service.ReportTargetHandlers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 콘텐츠 숨김·해제(005 FR-041, research M1). 대상 종류의 처리기({@link ReportTargetHandler#hide}·{@code unhide})를 부르고, 상태가
 * 바뀐 경우에만 작업 기록 {@code CONTENT_HIDE}·{@code CONTENT_UNHIDE}(before·after {@code status}, 사유)을 남긴다. 신고 처리의
 * {@code HIDE_CONTENT}도 이 서비스를 쓴다. 결과는 바뀐 뒤의 미리보기.
 */
@Service
public class ContentHideService {

    private final ReportTargetHandlers handlers;
    private final ReportTargetPreviewRepository previews;
    private final AdminAuditService auditService;

    public ContentHideService(ReportTargetHandlers handlers, ReportTargetPreviewRepository previews,
            AdminAuditService auditService) {
        this.handlers = handlers;
        this.previews = previews;
        this.auditService = auditService;
    }

    /** 숨긴다. 사유 1~500자 필수. 없거나 삭제된 대상 404 {@code CONTENT_NOT_FOUND}. 이미 숨김이면 그대로. */
    @Transactional
    public ReportTargetPreview hide(long adminId, ReportTargetType type, long id, String reason, String requestIp) {
        String normalized = SuspensionService.reason(reason, true, "reason");
        HideChange change = handlers.require(type).hide(id);
        record(adminId, AuditActions.CONTENT_HIDE, type, id, change, normalized, requestIp);
        return previews.preview(type, id);
    }

    /** 숨김을 푼다(사유 선택). 숨김이 아니면 그대로. */
    @Transactional
    public ReportTargetPreview unhide(long adminId, ReportTargetType type, long id, String reason, String requestIp) {
        String normalized = SuspensionService.reason(reason, false, "reason");
        HideChange change = handlers.require(type).unhide(id);
        record(adminId, AuditActions.CONTENT_UNHIDE, type, id, change, normalized, requestIp);
        return previews.preview(type, id);
    }

    private void record(long adminId, String action, ReportTargetType type, long id, HideChange change, String reason,
            String requestIp) {
        if (!change.changed()) {
            return;
        }
        auditService.record(adminId, action, auditTarget(type), id, null, Map.of("status", change.before()),
                Map.of("status", change.after()), reason, requestIp);
    }

    /** 작업 기록 {@code target_type}(신고 대상 종류와 같은 이름). */
    static String auditTarget(ReportTargetType type) {
        return switch (type) {
            case POST -> AuditActions.TARGET_POST;
            case COMMENT -> AuditActions.TARGET_COMMENT;
            case GUESTBOOK -> AuditActions.TARGET_GUESTBOOK;
            case TRACKBACK -> AuditActions.TARGET_TRACKBACK;
            default -> type.name();
        };
    }
}
