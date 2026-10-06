package net.java21.blog.backend.admin.portal;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.portal.dto.CreateCurationRequest;
import net.java21.blog.backend.admin.portal.dto.CurationResponse;
import net.java21.blog.backend.admin.portal.dto.UpdateCurationRequest;
import net.java21.blog.backend.admin.portal.repository.CurationQueryRepository;
import net.java21.blog.backend.admin.portal.repository.CurationRow;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.portal.domain.PortalCuration;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.portal.repository.PortalCurationRepository;
import net.java21.blog.backend.portal.repository.PortalExclusionRepository;
import net.java21.blog.backend.portal.repository.PortalExposure;
import net.java21.blog.backend.portal.repository.PortalIneligibility;
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
 * 운영자 추천(003 FR-091·092, research P10, 결정 표 13번).
 * <ul>
 *   <li>지정: 글이 있어야 하고(404 {@code POST_NOT_FOUND}) 지금 포털 노출 조건을 만족해야 한다(422 {@code POST_NOT_PORTAL_ELIGIBLE},
 *       이유는 {@code resultMessage}에만). 종료는 시작보다 늦어야 하고(400 field {@code endsAt}), 기간이 겹치는 추천이 이미 5개면
 *       409 {@code CURATION_LIMIT_EXCEEDED}(겹치는 행 수로 보수적으로 센다).</li>
 *   <li>수정: 기간·순서만, 같은 기간 검증(겹침 수에서 자기 자신 제외). 노출 조건은 다시 보지 않는다 — 조건을 잃은 추천도 기간을 끝내거나
 *       고칠 수 있어야 하고, 메인에서는 어차피 빠진다(FR-092).</li>
 *   <li>모든 쓰기는 작업 기록({@code CURATION_*})과 커밋 뒤 포털 캐시 비우기.</li>
 * </ul>
 */
@Service
public class AdminCurationService {

    static final int MAX_OVERLAPPING = 5;

    private final PortalCurationRepository curationRepository;
    private final CurationQueryRepository queryRepository;
    private final PostRepository postRepository;
    private final PortalExclusionRepository exclusionRepository;
    private final PortalCriteriaFactory criteriaFactory;
    private final UserRepository userRepository;
    private final AdminAuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AdminCurationService(PortalCurationRepository curationRepository, CurationQueryRepository queryRepository,
            PostRepository postRepository, PortalExclusionRepository exclusionRepository,
            PortalCriteriaFactory criteriaFactory, UserRepository userRepository, AdminAuditService auditService,
            ApplicationEventPublisher events, Clock clock) {
        this.curationRepository = curationRepository;
        this.queryRepository = queryRepository;
        this.postRepository = postRepository;
        this.exclusionRepository = exclusionRepository;
        this.criteriaFactory = criteriaFactory;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    /** 상태별 목록. {@code status}가 허용 값이 아니면 400(field {@code status}, {@code INVALID}, {@code params.allowed}). */
    @Transactional(readOnly = true)
    public Page<CurationResponse> list(String status, Pageable pageable) {
        CurationStatus parsed = parseStatus(status);
        Instant now = clock.instant();
        Page<CurationRow> page = queryRepository.findCurations(parsed, now, pageable);
        Set<Long> eligible = queryRepository.findPortalVisiblePostIds(
                page.getContent().stream().map(CurationRow::postId).collect(Collectors.toSet()), criteriaFactory.now());
        return page.map(row -> CurationResponse.of(row, now, eligible.contains(row.postId())));
    }

    @Transactional
    public CurationResponse create(long adminId, CreateCurationRequest request, String requestIp) {
        if (request.postId() == null) {
            throw invalid(FieldError.of("postId", "REQUIRED"));
        }
        validatePeriod(request.startsAt(), request.endsAt());
        Post post = postRepository.findWithBlogAndOwner(request.postId())
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND, "Post not found: " + request.postId()));
        List<PortalIneligibility> reasons = PortalExposure.evaluate(post, criteriaFactory.now(),
                exclusionRepository.existsByPostId(post.getId()));
        if (!reasons.isEmpty()) {
            throw new BusinessException(ErrorCode.POST_NOT_PORTAL_ELIGIBLE, "Post is not portal eligible: "
                    + reasons.stream().map(Enum::name).collect(Collectors.joining(", ")));
        }
        requireRoom(request.startsAt(), request.endsAt(), null);
        int sortOrder = request.sortOrder() == null ? 0 : request.sortOrder();
        PortalCuration curation = curationRepository.saveAndFlush(new PortalCuration(post, request.startsAt(),
                request.endsAt(), sortOrder, userRepository.getReferenceById(adminId)));
        auditService.record(adminId, AuditActions.CURATION_CREATE, AuditActions.TARGET_CURATION, curation.getId(),
                null, values(post.getId(), curation), requestIp);
        events.publishEvent(new PortalChangedEvent("curation:create"));
        return response(curation.getId());
    }

    @Transactional
    public CurationResponse update(long adminId, long id, UpdateCurationRequest request, String requestIp) {
        PortalCuration curation = require(id);
        Instant startsAt = request.startsAt() != null ? request.startsAt() : curation.getStartsAt();
        Instant endsAt = request.endsAt() != null ? request.endsAt() : curation.getEndsAt();
        int sortOrder = request.sortOrder() != null ? request.sortOrder() : curation.getSortOrder();
        validatePeriod(startsAt, endsAt);
        requireRoom(startsAt, endsAt, id);
        Map<String, Object> before = values(curation.getPost().getId(), curation);
        curation.reschedule(startsAt, endsAt, sortOrder);
        curationRepository.flush();
        auditService.record(adminId, AuditActions.CURATION_UPDATE, AuditActions.TARGET_CURATION, id, before,
                values(curation.getPost().getId(), curation), requestIp);
        events.publishEvent(new PortalChangedEvent("curation:update"));
        return response(id);
    }

    @Transactional
    public void delete(long adminId, long id, String requestIp) {
        PortalCuration curation = require(id);
        Map<String, Object> before = values(curation.getPost().getId(), curation);
        curationRepository.delete(curation);
        auditService.record(adminId, AuditActions.CURATION_DELETE, AuditActions.TARGET_CURATION, id, before, null,
                requestIp);
        events.publishEvent(new PortalChangedEvent("curation:delete"));
    }

    private CurationResponse response(long id) {
        CurationRow row = queryRepository.findCuration(id);
        boolean eligible = queryRepository.findPortalVisiblePostIds(Set.of(row.postId()), criteriaFactory.now())
                .contains(row.postId());
        return CurationResponse.of(row, clock.instant(), eligible);
    }

    private PortalCuration require(long id) {
        return curationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CURATION_NOT_FOUND, "Curation not found: " + id));
    }

    private void requireRoom(Instant startsAt, Instant endsAt, Long self) {
        if (queryRepository.countOverlapping(startsAt, endsAt, self) >= MAX_OVERLAPPING) {
            throw new BusinessException(ErrorCode.CURATION_LIMIT_EXCEEDED,
                    "Already " + MAX_OVERLAPPING + " curations in this period");
        }
    }

    private static void validatePeriod(Instant startsAt, Instant endsAt) {
        if (startsAt == null) {
            throw invalid(FieldError.of("startsAt", "REQUIRED"));
        }
        if (endsAt == null) {
            throw invalid(FieldError.of("endsAt", "REQUIRED"));
        }
        if (!endsAt.isAfter(startsAt)) {
            throw invalid(FieldError.of("endsAt", "INVALID"));
        }
    }

    static CurationStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return Arrays.stream(CurationStatus.values()).filter(s -> s.name().equals(status)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid status",
                        List.of(new FieldError("status", "INVALID", Map.of("allowed",
                                Arrays.stream(CurationStatus.values()).map(Enum::name).toList())))));
    }

    private static Map<String, Object> values(Long postId, PortalCuration curation) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("postId", postId);
        map.put("startsAt", curation.getStartsAt().toString());
        map.put("endsAt", curation.getEndsAt().toString());
        map.put("sortOrder", curation.getSortOrder());
        return map;
    }

    private static BusinessException invalid(FieldError error) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(error));
    }
}
