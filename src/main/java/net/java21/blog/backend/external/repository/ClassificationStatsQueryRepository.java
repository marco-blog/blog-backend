package net.java21.blog.backend.external.repository;

import static net.java21.blog.backend.external.domain.QClassificationReview.classificationReview;
import static net.java21.blog.backend.external.domain.QExternalBlog.externalBlog;
import static net.java21.blog.backend.external.domain.QExternalPost.externalPost;

import java.time.Instant;
import java.util.List;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.ReviewStatus;
import net.java21.blog.backend.external.domain.TopicSource;
import org.springframework.stereotype.Repository;

/** 분류 현황 집계(007 FR-122, research E11). 메서드마다 쿼리 1회, 모두 4회. */
@Repository
public class ClassificationStatsQueryRepository {

    /** 표본 수와 맞은 수. */
    public record Ratio(long sample, long hit) {
    }

    /** 주제·출처별 글 수. */
    public record SourceCount(long topicId, TopicSource source, long count) {
    }

    private final JPAQueryFactory queryFactory;

    public ClassificationStatsQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** {@code since} 이후 사람이 정한(OWNER·REVIEW) 글 중 분류 예측이 있는 것과, 그중 예측이 최종 주제와 같은 것. */
    public Ratio classifierAccuracy(Instant since) {
        NumberExpression<Long> hit = new CaseBuilder()
                .when(externalPost.classifierTopic.id.eq(externalPost.topic.id)).then(1L).otherwise(0L).sumLong();
        Tuple row = queryFactory.select(externalPost.count(), hit)
                .from(externalPost)
                .where(externalPost.topicSource.in(TopicSource.OWNER, TopicSource.REVIEW),
                        externalPost.classifierTopic.isNotNull(),
                        externalPost.topicDecidedAt.goe(since))
                .fetchOne();
        return ratio(row, externalPost.count(), hit);
    }

    /** {@code since} 이후 확정한 검수와, 그중 확정 주제가 블로그 기본 주제와 같은 것. */
    public Ratio finalAccuracy(Instant since) {
        NumberExpression<Long> unchanged = new CaseBuilder()
                .when(classificationReview.confirmedTopic.id.eq(externalBlog.defaultTopic.id)).then(1L).otherwise(0L)
                .sumLong();
        Tuple row = queryFactory.select(classificationReview.count(), unchanged)
                .from(classificationReview)
                .join(classificationReview.externalPost, externalPost)
                .join(externalPost.externalBlog, externalBlog)
                .where(classificationReview.status.eq(ReviewStatus.CONFIRMED),
                        classificationReview.reviewedAt.goe(since))
                .fetchOne();
        return ratio(row, classificationReview.count(), unchanged);
    }

    /** {@code since} 이후 발행된 ACTIVE 글의 주제·출처별 수. */
    public List<SourceCount> distribution(Instant since) {
        return queryFactory.select(externalPost.topic.id, externalPost.topicSource, externalPost.count())
                .from(externalPost)
                .where(externalPost.status.eq(ExternalPostStatus.ACTIVE), externalPost.publishedAt.goe(since))
                .groupBy(externalPost.topic.id, externalPost.topicSource)
                .fetch().stream()
                .map(t -> new SourceCount(t.get(externalPost.topic.id), t.get(externalPost.topicSource),
                        value(t.get(externalPost.count()))))
                .toList();
    }

    /** ACTIVE 글의 대기 검수 수. */
    public long pendingReviews() {
        return value(queryFactory.select(classificationReview.count())
                .from(classificationReview)
                .join(classificationReview.externalPost, externalPost)
                .where(classificationReview.status.eq(ReviewStatus.PENDING),
                        externalPost.status.eq(ExternalPostStatus.ACTIVE))
                .fetchOne());
    }

    private static Ratio ratio(Tuple row, NumberExpression<Long> sample, NumberExpression<Long> hit) {
        return row == null ? new Ratio(0, 0) : new Ratio(value(row.get(sample)), value(row.get(hit)));
    }

    private static long value(Number n) {
        return n == null ? 0 : n.longValue();
    }
}
