package net.java21.blog.backend.external.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ReviewStatus;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.topic.domain.Topic;

/** 분류 검수(007 contracts/api.md ClassificationReview). 제목·요약은 외부에서 온 일반 텍스트다. */
public record ClassificationReviewResponse(long id, ReviewStatus status, PostRef post, BlogRef externalBlog,
        Long predictedTopicId, BigDecimal confidence, Long confirmedTopicId, AdminRef reviewedBy, Instant reviewedAt,
        Instant createdAt) {

    public record PostRef(long id, String title, String summary, String link, List<String> feedTerms, long topicId,
            TopicSource topicSource) {
    }

    public record BlogRef(long id, String title, long defaultTopicId) {
    }

    public static ClassificationReviewResponse of(ClassificationReview r) {
        ExternalPost p = r.getExternalPost();
        ExternalBlog b = p.getExternalBlog();
        return new ClassificationReviewResponse(r.getId(), r.getStatus(),
                new PostRef(p.getId(), p.getTitle(), p.getSummary(), p.getLink(),
                        p.getFeedTerms() == null ? List.of() : p.getFeedTerms(), p.getTopic().getId(),
                        p.getTopicSource()),
                new BlogRef(b.getId(), b.getTitle(), b.getDefaultTopic().getId()), idOf(r.getPredictedTopic()),
                r.getConfidence(), idOf(r.getConfirmedTopic()),
                r.getReviewedBy() == null ? null
                        : new AdminRef(r.getReviewedBy().getId(), r.getReviewedBy().getNickname()),
                r.getReviewedAt(), r.getCreatedAt());
    }

    private static Long idOf(Topic topic) {
        return topic == null ? null : topic.getId();
    }
}
