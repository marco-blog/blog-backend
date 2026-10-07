package net.java21.blog.backend.external.repository;

import static net.java21.blog.backend.external.domain.QClassificationReview.classificationReview;
import static net.java21.blog.backend.external.domain.QExternalBlog.externalBlog;
import static net.java21.blog.backend.external.domain.QExternalPost.externalPost;

import java.util.List;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.ReviewStatus;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 검수 목록(007 FR-121, T061): ACTIVE 글의 검수만, 오래된 것 먼저(같으면 id). 글·블로그·검토자를 함께 읽어 목록 1회 + 수 1회다.
 */
@Repository
public class ClassificationReviewQueryRepository {

    private static final QUser reviewer = new QUser("reviewer");

    private final JPAQueryFactory queryFactory;

    public ClassificationReviewQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<ClassificationReview> findPage(ReviewStatus status, Long externalBlogId, Pageable pageable) {
        BooleanExpression where = classificationReview.status.eq(status)
                .and(externalPost.status.eq(ExternalPostStatus.ACTIVE))
                .and(externalBlogId == null ? null : externalBlog.id.eq(externalBlogId));
        List<ClassificationReview> content = queryFactory.selectFrom(classificationReview)
                .join(classificationReview.externalPost, externalPost).fetchJoin()
                .join(externalPost.externalBlog, externalBlog).fetchJoin()
                .leftJoin(classificationReview.reviewedBy, reviewer).fetchJoin()
                .where(where)
                .orderBy(classificationReview.createdAt.asc(), classificationReview.id.asc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(classificationReview.count())
                .from(classificationReview)
                .join(classificationReview.externalPost, externalPost)
                .join(externalPost.externalBlog, externalBlog)
                .where(where)
                .fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }
}
