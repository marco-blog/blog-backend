package net.java21.blog.backend.admin.external;

import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.dto.AdminExternalPostResponse;
import net.java21.blog.backend.external.dto.ExternalExclusionResponse;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.portal.repository.PortalExclusionRepository;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 외부 글 조치(007 FR-127, FR-123): 내림({@code REMOVED}·{@code ADMIN}, 되돌리지 않음)과 포털 제외(되돌릴 수 있음, 003 규칙과
 * 같게 멱등·사유 변경). 작업 기록 대상은 {@code EXTERNAL_POST}, 커밋 뒤 포털 캐시 무효화.
 */
@Service
public class AdminExternalPostService {

    private final ExternalPostRepository postRepository;
    private final PortalExclusionRepository exclusionRepository;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final ApplicationEventPublisher events;

    public AdminExternalPostService(ExternalPostRepository postRepository,
            PortalExclusionRepository exclusionRepository, UserRepository userRepository,
            AdminAuditService auditService, ApplicationEventPublisher events) {
        this.postRepository = postRepository;
        this.exclusionRepository = exclusionRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.events = events;
    }

    /** 내림(사유 필수). 이미 내린 글은 409 {@code EXTERNAL_BLOG_STATE_CONFLICT}({@code status: REMOVED}). */
    @Transactional
    public AdminExternalPostResponse remove(long adminId, long postId, String reason, String requestIp) {
        String text = AdminExternalBlogService.requireText("reason", reason);
        ExternalPost post = requirePost(postId);
        if (!post.remove(RemovedReason.ADMIN)) {
            throw BusinessException.withParams(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT,
                    "External post already removed: " + postId,
                    Map.of("status", ExternalPostStatus.REMOVED.name(), "action", "remove"));
        }
        postRepository.flush();
        auditService.record(adminId, AuditActions.EXTERNAL_POST_REMOVE, AuditActions.TARGET_EXTERNAL_POST, postId,
                null, AdminExternalBlogService.value("status", ExternalPostStatus.ACTIVE.name()),
                AdminExternalBlogService.value("status", ExternalPostStatus.REMOVED.name()), text, requestIp);
        events.publishEvent(new PortalChangedEvent("external:remove-post"));
        PortalExclusion exclusion = exclusionRepository.findByExternalPostId(postId).orElse(null);
        return AdminExternalPostResponse.of(post, post.getExternalBlog().isOwnershipVerified(), exclusion);
    }

    /**
     * 신고 처리의 "포털에서 내림"({@code REPORT}). 이미 내린 글이면 그대로 둔다(같은 대상의 신고를 닫을 수 있게). 작업 기록은 내림을
     * 했을 때만 {@code EXTERNAL_POST_REMOVE}(신고 처리 자체는 005 {@code REPORT_ACTION}).
     */
    @Transactional
    public void removeForReport(long adminId, long postId, String reason, String requestIp) {
        ExternalPost post = postRepository.findWithBlog(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND,
                        "Content not found: EXTERNAL_POST " + postId));
        if (!post.remove(RemovedReason.REPORT)) {
            return;
        }
        postRepository.flush();
        auditService.record(adminId, AuditActions.EXTERNAL_POST_REMOVE, AuditActions.TARGET_EXTERNAL_POST, postId,
                null, AdminExternalBlogService.value("status", ExternalPostStatus.ACTIVE.name()),
                AdminExternalBlogService.value("status", ExternalPostStatus.REMOVED.name()), reason, requestIp);
        events.publishEvent(new PortalChangedEvent("external:report-remove"));
    }

    /** 포털 제외하거나 사유를 바꾼다(작업 기록 {@code PORTAL_EXCLUDE}, 전후 사유). */
    @Transactional
    public ExternalExclusionResponse exclude(long adminId, long postId, String reason, String requestIp) {
        String text = AdminExternalBlogService.requireText("reason", reason);
        ExternalPost post = requirePost(postId);
        PortalExclusion existing = exclusionRepository.findByExternalPostId(postId).orElse(null);
        String before = existing == null ? null : existing.getReason();
        PortalExclusion saved;
        if (existing == null) {
            saved = exclusionRepository.saveAndFlush(
                    new PortalExclusion(post, text, userRepository.getReferenceById(adminId)));
        } else {
            existing.changeReason(text, userRepository.getReferenceById(adminId));
            exclusionRepository.flush();
            saved = existing;
        }
        auditService.record(adminId, AuditActions.PORTAL_EXCLUDE, AuditActions.TARGET_EXTERNAL_POST, postId, null,
                AdminExternalBlogService.value("reason", before), AdminExternalBlogService.value("reason", text),
                text, requestIp);
        events.publishEvent(new PortalChangedEvent("external:exclude"));
        return ExternalExclusionResponse.of(exclusionRepository.findByExternalPostIds(List.of(postId))
                .stream().findFirst().orElse(saved));
    }

    /** 포털 제외 해제. 제외되지 않은 글이면 404 {@code PORTAL_EXCLUSION_NOT_FOUND}. */
    @Transactional
    public void unexclude(long adminId, long postId, String requestIp) {
        PortalExclusion existing = exclusionRepository.findByExternalPostId(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PORTAL_EXCLUSION_NOT_FOUND,
                        "External post is not excluded: " + postId));
        exclusionRepository.delete(existing);
        auditService.record(adminId, AuditActions.PORTAL_UNEXCLUDE, AuditActions.TARGET_EXTERNAL_POST, postId,
                AdminExternalBlogService.value("reason", existing.getReason()),
                AdminExternalBlogService.value("reason", null), requestIp);
        events.publishEvent(new PortalChangedEvent("external:unexclude"));
    }

    private ExternalPost requirePost(long postId) {
        return postRepository.findWithBlog(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXTERNAL_POST_NOT_FOUND,
                        "External post not found: " + postId));
    }
}
