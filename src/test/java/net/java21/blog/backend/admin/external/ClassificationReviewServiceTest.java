package net.java21.blog.backend.admin.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.audit.AdminAuditLog;
import net.java21.blog.backend.admin.audit.AdminAuditLogRepository;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ReviewStatus;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.ClassificationReviewResponse;
import net.java21.blog.backend.external.repository.ClassificationReviewQueryRepository;
import net.java21.blog.backend.external.repository.ClassificationReviewRepository;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.repository.TopicRepository;
import net.java21.blog.backend.topic.service.TopicService;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;

/** 007 T061: 검수 확정·일괄 확정(US3 AS2·AS3, FR-121). 저장소는 H2. */
@JpaRepositoryTest
@Import(ClassificationReviewQueryRepository.class)
class ClassificationReviewServiceTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private ClassificationReviewRepository reviewRepository;
    @Autowired
    private ClassificationReviewQueryRepository queryRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TopicRepository topicRepository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    private ApplicationEventPublisher events;
    private ClassificationReviewService service;
    private ExternalFixtures x;
    private User admin;
    private Topic major;
    private Topic it;
    private Topic science;
    private ExternalBlog blog;

    @BeforeEach
    void setUp() {
        JpaFixtures f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        admin = f.user("admin");
        major = f.topic(null, "knowledge", 1);
        it = f.topic(major, "it-internet", 1);
        science = f.topic(major, "science", 2);
        events = mock(ApplicationEventPublisher.class);
        TopicService topics = new TopicService(topicRepository, null, null, null, null, null,
                PortalProperties.defaults());
        service = new ClassificationReviewService(reviewRepository, queryRepository, userRepository, topics,
                new AdminAuditService(auditLogRepository, userRepository), events,
                new MutableClock(JpaFixtures.T0));
        blog = x.blog(null, it, ExternalBlogStatus.ACTIVE);
    }

    private ClassificationReview pending(String title) {
        ExternalPost post = x.post(blog, title, it, null);
        return x.review(post, science, 0.4);
    }

    private List<AdminAuditLog> logs(long reviewId) {
        return auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("CLASSIFICATION_REVIEW", reviewId);
    }

    @Test
    void confirmSetsReviewTopicAndRecordsAudit() {
        ClassificationReview review = pending("P");
        em.flush();

        ClassificationReviewResponse confirmed = service.confirm(admin.getId(), review.getId(), science.getId(),
                "127.0.0.1");

        assertThat(confirmed.status()).isEqualTo(ReviewStatus.CONFIRMED);
        assertThat(confirmed.confirmedTopicId()).isEqualTo(science.getId());
        assertThat(confirmed.post().topicSource()).isEqualTo(TopicSource.REVIEW);
        assertThat(confirmed.reviewedBy().userId()).isEqualTo(admin.getId());
        ExternalPost post = review.getExternalPost();
        assertThat(post.getTopic().getId()).isEqualTo(science.getId());
        assertThat(post.getTopicDecidedAt()).isEqualTo(JpaFixtures.T0);
        AdminAuditLog log = logs(review.getId()).getFirst();
        assertThat(log.getAction()).isEqualTo("CLASSIFICATION_CONFIRM");
        assertThat(log.getBefore()).containsEntry("topicId", it.getId());
        assertThat(log.getAfter()).containsEntry("topicId", science.getId());
        verify(events).publishEvent(new PortalChangedEvent("external:review-confirm"));
        assertThat(service.list(null, null, PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    void confirmRejectsClosedMissingAndInvalidTopic() {
        ClassificationReview review = pending("P");
        em.flush();

        assertThatThrownBy(() -> service.confirm(admin.getId(), review.getId(), major.getId(), null))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.TOPIC_NOT_SELECTABLE);
        assertThatThrownBy(() -> service.confirm(admin.getId(), review.getId(), null, null))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThatThrownBy(() -> service.confirm(admin.getId(), 999_999L, it.getId(), null))
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.CLASSIFICATION_REVIEW_NOT_FOUND);
        review.skip();
        em.flush();
        assertThatThrownBy(() -> service.confirm(admin.getId(), review.getId(), it.getId(), null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CLASSIFICATION_REVIEW_CLOSED);
                    assertThat(e.params()).containsEntry("status", "SKIPPED");
                });
        verify(events, never()).publishEvent(new PortalChangedEvent("external:review-confirm"));
    }

    @Test
    void batchConfirmsOpenOnesSkipsClosedAndInvalidatesOnce() {
        ClassificationReview a = pending("A");
        ClassificationReview b = pending("B");
        ClassificationReview closed = pending("C");
        closed.skip();
        em.flush();

        ClassificationReviewService.BatchResult result = service.confirmBatch(admin.getId(), List.of(
                new ClassificationReviewService.Item(a.getId(), science.getId()),
                new ClassificationReviewService.Item(b.getId(), it.getId()),
                new ClassificationReviewService.Item(a.getId(), it.getId()),
                new ClassificationReviewService.Item(closed.getId(), it.getId()),
                new ClassificationReviewService.Item(999_999L, it.getId())), null);

        assertThat(result.confirmed()).containsExactly(a.getId(), b.getId());
        assertThat(result.skipped()).containsExactly(
                new ClassificationReviewService.Skipped(closed.getId(), "SKIPPED"),
                new ClassificationReviewService.Skipped(999_999L, "NOT_FOUND"));
        assertThat(a.getExternalPost().getTopic().getId()).isEqualTo(science.getId());
        assertThat(b.getExternalPost().getTopicSource()).isEqualTo(TopicSource.REVIEW);
        assertThat(logs(a.getId())).hasSize(1);
        assertThat(logs(b.getId())).hasSize(1);
        assertThat(logs(closed.getId())).isEmpty();
        verify(events, times(1)).publishEvent(new PortalChangedEvent("external:review-confirm"));
    }

    @Test
    void batchValidatesSizeAndItems() {
        List<ClassificationReviewService.Item> tooMany = new ArrayList<>();
        for (long i = 1; i <= 51; i++) {
            tooMany.add(new ClassificationReviewService.Item(i, it.getId()));
        }
        for (List<ClassificationReviewService.Item> items : List.of(List.<ClassificationReviewService.Item>of(),
                tooMany, List.of(new ClassificationReviewService.Item(null, it.getId())),
                List.of(new ClassificationReviewService.Item(1L, null)))) {
            assertThatThrownBy(() -> service.confirmBatch(admin.getId(), items, null))
                    .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        }
        assertThatThrownBy(() -> service.confirmBatch(admin.getId(), null, null))
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);

        ClassificationReview closed = pending("Z");
        closed.skip();
        em.flush();
        ClassificationReviewService.BatchResult none = service.confirmBatch(admin.getId(),
                List.of(new ClassificationReviewService.Item(closed.getId(), it.getId())), null);
        assertThat(none.confirmed()).isEmpty();
        verify(events, never()).publishEvent(new PortalChangedEvent("external:review-confirm"));
    }
}
