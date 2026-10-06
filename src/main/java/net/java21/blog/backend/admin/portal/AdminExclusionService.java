package net.java21.blog.backend.admin.portal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.portal.dto.AdminPortalPostResponse;
import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.admin.portal.dto.ExclusionResponse;
import net.java21.blog.backend.admin.portal.repository.CurationQueryRepository;
import net.java21.blog.backend.admin.portal.repository.ExclusionRow;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.portal.repository.PortalExclusionRepository;
import net.java21.blog.backend.portal.repository.PortalExposure;
import net.java21.blog.backend.portal.service.PortalCriteriaFactory;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포털 제외(003 FR-093, research P10, 결정 표 14번)와 콘솔의 글 찾기. 제외는 글 상태와 관계없이 할 수 있고(없는 글만 404),
 * {@code PUT}은 없으면 만들고 있으면 사유만 바꾼다(멱등). 해제는 행 삭제이며 이력은 작업 기록에 남는다. 포털에만 영향이 있다.
 */
@Service
public class AdminExclusionService {

    static final int REASON_MAX = PortalExclusion.REASON_MAX;

    private final PortalExclusionRepository exclusionRepository;
    private final CurationQueryRepository queryRepository;
    private final PostRepository postRepository;
    private final PortalCriteriaFactory criteriaFactory;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final ApplicationEventPublisher events;

    public AdminExclusionService(PortalExclusionRepository exclusionRepository,
            CurationQueryRepository queryRepository, PostRepository postRepository,
            PortalCriteriaFactory criteriaFactory, UserRepository userRepository, AdminAuditService auditService,
            ApplicationEventPublisher events) {
        this.exclusionRepository = exclusionRepository;
        this.queryRepository = queryRepository;
        this.postRepository = postRepository;
        this.criteriaFactory = criteriaFactory;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Page<ExclusionResponse> list(Pageable pageable) {
        return queryRepository.findExclusions(pageable).map(ExclusionResponse::of);
    }

    /** 제외하거나 사유를 바꾼다. 작업 기록은 모두 {@code PORTAL_EXCLUDE}(전후 사유). */
    @Transactional
    public ExclusionResponse exclude(long adminId, long postId, String reason, String requestIp) {
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty()) {
            throw invalid(FieldError.of("reason", "REQUIRED"));
        }
        if (text.length() > REASON_MAX) {
            throw invalid(new FieldError("reason", "TOO_LONG", Map.of("max", REASON_MAX)));
        }
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId));
        PortalExclusion existing = exclusionRepository.findByPostId(postId).orElse(null);
        String before = existing == null ? null : existing.getReason();
        if (existing == null) {
            exclusionRepository.saveAndFlush(new PortalExclusion(post, text, userRepository.getReferenceById(adminId)));
        } else {
            existing.changeReason(text, userRepository.getReferenceById(adminId));
            exclusionRepository.flush();
        }
        auditService.record(adminId, AuditActions.PORTAL_EXCLUDE, AuditActions.TARGET_POST, postId, null,
                reasonValue(before), reasonValue(text), text, requestIp);
        events.publishEvent(new PortalChangedEvent("portal:exclude"));
        return ExclusionResponse.of(queryRepository.findExclusion(postId));
    }

    /** 제외 해제. 제외되지 않은 글이면 404 {@code PORTAL_EXCLUSION_NOT_FOUND}. */
    @Transactional
    public void unexclude(long adminId, long postId, String requestIp) {
        PortalExclusion existing = exclusionRepository.findByPostId(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PORTAL_EXCLUSION_NOT_FOUND,
                        "Post is not excluded: " + postId));
        exclusionRepository.delete(existing);
        auditService.record(adminId, AuditActions.PORTAL_UNEXCLUDE, AuditActions.TARGET_POST, postId,
                reasonValue(existing.getReason()), reasonValue(null), requestIp);
        events.publishEvent(new PortalChangedEvent("portal:unexclude"));
    }

    /** 콘솔의 글 찾기: 포털 노출 여부와 이유, 제외 정보. 없는 글 404 {@code POST_NOT_FOUND}. */
    @Transactional(readOnly = true)
    public AdminPortalPostResponse lookup(long postId) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId));
        ExclusionRow exclusion = queryRepository.findExclusion(postId);
        List<String> reasons = PortalExposure.evaluate(post, criteriaFactory.now(), exclusion != null).stream()
                .map(Enum::name).toList();
        return new AdminPortalPostResponse(post.getId(), post.getTitle(),
                new AdminPortalPostResponse.BlogRef(post.getBlog().getHandle(), post.getBlog().getTitle()),
                post.getStatus(), post.getVisibility(), post.getPublishedAt(), reasons.isEmpty(), reasons,
                exclusion == null ? null : new AdminPortalPostResponse.Excluded(exclusion.reason(),
                        new AdminRef(exclusion.excludedById(), exclusion.excludedByNickname()), exclusion.createdAt()));
    }

    private static Map<String, Object> reasonValue(String reason) {
        Map<String, Object> map = new HashMap<>();
        map.put("reason", reason);
        return map;
    }

    private static BusinessException invalid(FieldError error) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(error));
    }
}
