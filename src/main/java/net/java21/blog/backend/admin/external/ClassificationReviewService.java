package net.java21.blog.backend.admin.external;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ReviewStatus;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.ClassificationReviewResponse;
import net.java21.blog.backend.external.repository.ClassificationReviewQueryRepository;
import net.java21.blog.backend.external.repository.ClassificationReviewRepository;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.service.TopicService;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분류 검수(007 FR-121, US3 AS2·AS3). 확정하면 글 주제가 그 주제(출처 {@code REVIEW})로 바뀌고 사람이 정한 시각이 남는다. 대기(PENDING)가
 * 아니면 409 {@code CLASSIFICATION_REVIEW_CLOSED}. 확정마다 작업 기록 {@code CLASSIFICATION_CONFIRM}(전후 주제). 포털 캐시는 요청당 한 번
 * 무효화한다.
 */
@Service
public class ClassificationReviewService {

    public static final int BATCH_MAX = 50;

    /** 일괄 확정 결과. {@code skipped}는 이미 닫혔거나 없는 검수({@code status}: 지금 상태 또는 {@code NOT_FOUND}). */
    public record BatchResult(List<Long> confirmed, List<Skipped> skipped) {
    }

    public record Skipped(long id, String status) {
    }

    public record Item(Long id, Long topicId) {
    }

    private final ClassificationReviewRepository reviewRepository;
    private final ClassificationReviewQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final TopicService topicService;
    private final AdminAuditService auditService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public ClassificationReviewService(ClassificationReviewRepository reviewRepository,
            ClassificationReviewQueryRepository queryRepository, UserRepository userRepository,
            TopicService topicService, AdminAuditService auditService, ApplicationEventPublisher events, Clock clock) {
        this.reviewRepository = reviewRepository;
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
        this.topicService = topicService;
        this.auditService = auditService;
        this.events = events;
        this.clock = clock;
    }

    /** 목록. {@code status}가 없으면 PENDING. */
    @Transactional(readOnly = true)
    public Page<ClassificationReviewResponse> list(ReviewStatus status, Long externalBlogId, Pageable pageable) {
        return queryRepository.findPage(status == null ? ReviewStatus.PENDING : status, externalBlogId, pageable)
                .map(ClassificationReviewResponse::of);
    }

    @Transactional
    public ClassificationReviewResponse confirm(long adminId, long id, Long topicId, String requestIp) {
        if (topicId == null) {
            throw invalid(FieldError.of("topicId", "REQUIRED"));
        }
        ClassificationReview review = reviewRepository.findAllWithPost(List.of(id)).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.CLASSIFICATION_REVIEW_NOT_FOUND,
                        "Classification review not found: " + id));
        if (review.getStatus() != ReviewStatus.PENDING) {
            throw review.closed();
        }
        apply(adminId, review, topicId, clock.instant(), requestIp);
        reviewRepository.flush();
        events.publishEvent(new PortalChangedEvent("external:review-confirm"));
        return ClassificationReviewResponse.of(review);
    }

    /** 1~50개를 한 번에. 같은 id가 두 번 오면 앞의 것만. 닫힌 것과 없는 것은 건너뛴다. */
    @Transactional
    public BatchResult confirmBatch(long adminId, List<Item> items, String requestIp) {
        if (items == null || items.isEmpty()) {
            throw invalid(FieldError.of("items", "REQUIRED"));
        }
        if (items.size() > BATCH_MAX) {
            throw invalid(new FieldError("items", "TOO_LONG", Map.of("max", BATCH_MAX)));
        }
        Map<Long, Long> wanted = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            if (item == null || item.id() == null) {
                throw invalid(FieldError.of("items[" + i + "].id", "REQUIRED"));
            }
            if (item.topicId() == null) {
                throw invalid(FieldError.of("items[" + i + "].topicId", "REQUIRED"));
            }
            wanted.putIfAbsent(item.id(), item.topicId());
        }
        Map<Long, ClassificationReview> found = reviewRepository.findAllWithPost(wanted.keySet()).stream()
                .collect(Collectors.toMap(ClassificationReview::getId, Function.identity()));
        Instant now = clock.instant();
        List<Long> confirmed = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Map.Entry<Long, Long> entry : wanted.entrySet()) {
            ClassificationReview review = found.get(entry.getKey());
            if (review == null) {
                skipped.add(new Skipped(entry.getKey(), "NOT_FOUND"));
            } else if (review.getStatus() != ReviewStatus.PENDING) {
                skipped.add(new Skipped(review.getId(), review.getStatus().name()));
            } else {
                apply(adminId, review, entry.getValue(), now, requestIp);
                confirmed.add(review.getId());
            }
        }
        if (!confirmed.isEmpty()) {
            reviewRepository.flush();
            events.publishEvent(new PortalChangedEvent("external:review-confirm"));
        }
        return new BatchResult(confirmed, skipped);
    }

    private void apply(long adminId, ClassificationReview review, long topicId, Instant now, String requestIp) {
        ExternalPost post = review.getExternalPost();
        Long before = post.getTopic().getId();
        Topic topic = topicService.requireSelectable(topicId, before);
        User admin = userRepository.getReferenceById(adminId);
        review.confirm(topic, admin, now);
        post.changeTopic(topic, TopicSource.REVIEW, now);
        auditService.record(adminId, AuditActions.CLASSIFICATION_CONFIRM, AuditActions.TARGET_CLASSIFICATION_REVIEW,
                review.getId(), AdminExternalBlogService.value("topicId", before),
                AdminExternalBlogService.value("topicId", topicId), requestIp);
    }

    static BusinessException invalid(FieldError error) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", List.of(error));
    }
}
