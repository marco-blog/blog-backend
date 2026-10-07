package net.java21.blog.backend.external.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import net.java21.blog.backend.common.domain.BaseTimeEntity;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * 분류 검수(classification_reviews, 007 FR-119·121). 자동 분류 신뢰도가 기준 미만인 새 글마다 하나. 운영자가 확정하면 CONFIRMED, 주인이
 * 먼저 주제를 정하면 SKIPPED. 글이 지워지면 DB의 {@code ON DELETE CASCADE}로 함께 지워진다.
 */
@Entity
@Table(name = "classification_reviews")
public class ClassificationReview extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "external_post_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ExternalPost externalPost;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "predicted_topic_id")
    private Topic predictedTopic;

    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReviewStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_topic_id")
    private Topic confirmedTopic;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected ClassificationReview() {
    }

    public ClassificationReview(ExternalPost externalPost, Topic predictedTopic, double confidence) {
        this.externalPost = externalPost;
        this.predictedTopic = predictedTopic;
        this.confidence = BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP);
        this.status = ReviewStatus.PENDING;
    }

    /** 운영자 확정. PENDING이 아니면 409 {@code CLASSIFICATION_REVIEW_CLOSED}. */
    public void confirm(Topic topic, User admin, Instant now) {
        if (status != ReviewStatus.PENDING) {
            throw closed();
        }
        this.status = ReviewStatus.CONFIRMED;
        this.confirmedTopic = topic;
        this.reviewedBy = admin;
        this.reviewedAt = now;
    }

    /** 주인이 먼저 정함. PENDING일 때만. */
    public void skip() {
        if (status == ReviewStatus.PENDING) {
            this.status = ReviewStatus.SKIPPED;
        }
    }

    public BusinessException closed() {
        return BusinessException.withParams(ErrorCode.CLASSIFICATION_REVIEW_CLOSED, "Review is " + status,
                java.util.Map.of("status", status.name()));
    }

    public Long getId() {
        return id;
    }

    public ExternalPost getExternalPost() {
        return externalPost;
    }

    public Topic getPredictedTopic() {
        return predictedTopic;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public Topic getConfirmedTopic() {
        return confirmedTopic;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
