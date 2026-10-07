package net.java21.blog.backend.external.repository;

import static net.java21.blog.backend.external.domain.QClassificationReview.classificationReview;
import static net.java21.blog.backend.external.domain.QExternalBlog.externalBlog;
import static net.java21.blog.backend.external.domain.QExternalPost.externalPost;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Expression;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.CaseBuilder;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.ReviewStatus;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 외부 블로그 목록·수집 선택(007 T027). 회원 목록은 글 수를 포함해 1회, 관리자 목록은 회원·검토자·글 수·검수 대기 수를 포함해 목록 1회
 * + 수 1회다(N+1 없음).
 */
@Repository
public class ExternalBlogQueryRepository {

    /** 회원 목록 상한(contracts/api.md). */
    public static final int MEMBER_LIST_MAX = 20;

    private static final QUser member = new QUser("member");
    private static final QUser reviewer = new QUser("reviewer");

    /** 등록과 그 글 수·검수 대기 수. */
    public record BlogRow(ExternalBlog blog, long postCount, long pendingReviewCount) {
    }

    private final JPAQueryFactory queryFactory;

    public ExternalBlogQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    private static Expression<Long> postCount() {
        return JPAExpressions.select(externalPost.count()).from(externalPost)
                .where(externalPost.externalBlog.id.eq(externalBlog.id),
                        externalPost.status.eq(ExternalPostStatus.ACTIVE));
    }

    private static Expression<Long> pendingReviewCount() {
        return JPAExpressions.select(classificationReview.count()).from(classificationReview)
                .where(classificationReview.externalPost.externalBlog.id.eq(externalBlog.id),
                        classificationReview.status.eq(ReviewStatus.PENDING),
                        classificationReview.externalPost.status.eq(ExternalPostStatus.ACTIVE));
    }

    /** 회원의 등록(만든 순 최근 먼저, 거절·해제 포함, 최대 20). 쿼리 1회. */
    public List<BlogRow> findMemberBlogs(long userId) {
        Expression<Long> posts = postCount();
        List<Tuple> rows = queryFactory.select(externalBlog, posts)
                .from(externalBlog)
                .where(externalBlog.member.id.eq(userId))
                .orderBy(externalBlog.createdAt.desc(), externalBlog.id.desc())
                .limit(MEMBER_LIST_MAX)
                .fetch();
        return rows.stream().map(t -> new BlogRow(t.get(externalBlog), count(t.get(posts)), 0)).toList();
    }

    /** 등록 하나와 글 수·검수 대기 수(회원·검토자 함께). 쿼리 1회. */
    public BlogRow findRow(long id) {
        Expression<Long> posts = postCount();
        Expression<Long> reviews = pendingReviewCount();
        Tuple t = queryFactory.select(externalBlog, posts, reviews)
                .from(externalBlog)
                .leftJoin(externalBlog.member, member).fetchJoin()
                .leftJoin(externalBlog.reviewedBy, reviewer).fetchJoin()
                .where(externalBlog.id.eq(id))
                .fetchOne();
        return t == null ? null : new BlogRow(t.get(externalBlog), count(t.get(posts)), count(t.get(reviews)));
    }

    /**
     * 관리자 목록(contracts/api.md): {@code status}가 없으면 PENDING 먼저, 그다음 최근 만든 순. {@code q}는 제목·피드 주소 부분 일치.
     */
    public Page<BlogRow> findAdminBlogs(ExternalBlogStatus status, String q, Pageable pageable) {
        BooleanExpression where = null;
        if (status != null) {
            where = externalBlog.status.eq(status);
        }
        if (q != null && !q.isBlank()) {
            String like = q.strip().toLowerCase(java.util.Locale.ROOT);
            BooleanExpression text = externalBlog.title.lower().contains(like)
                    .or(externalBlog.feedUrl.lower().contains(like));
            where = where == null ? text : where.and(text);
        }
        NumberExpression<Integer> pendingFirst = new CaseBuilder()
                .when(externalBlog.status.eq(ExternalBlogStatus.PENDING)).then(0).otherwise(1);
        List<OrderSpecifier<?>> order = status == null
                ? List.of(pendingFirst.asc(), externalBlog.createdAt.desc(), externalBlog.id.desc())
                : List.of(externalBlog.createdAt.desc(), externalBlog.id.desc());
        Expression<Long> posts = postCount();
        Expression<Long> reviews = pendingReviewCount();
        List<Tuple> rows = queryFactory.select(externalBlog, posts, reviews)
                .from(externalBlog)
                .leftJoin(externalBlog.member, member).fetchJoin()
                .leftJoin(externalBlog.reviewedBy, reviewer).fetchJoin()
                .where(where)
                .orderBy(order.toArray(OrderSpecifier[]::new))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(externalBlog.count()).from(externalBlog).where(where).fetchOne();
        List<BlogRow> content = rows.stream()
                .map(t -> new BlogRow(t.get(externalBlog), count(t.get(posts)), count(t.get(reviews))))
                .toList();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    /** 수집할 차례(ACTIVE, {@code next_fetch_at <= now}, 오래된 것 먼저, research E1). */
    public List<Long> findDueIds(Instant now, int limit) {
        return queryFactory.select(externalBlog.id)
                .from(externalBlog)
                .where(externalBlog.status.eq(ExternalBlogStatus.ACTIVE), externalBlog.nextFetchAt.loe(now))
                .orderBy(externalBlog.nextFetchAt.asc(), externalBlog.id.asc())
                .limit(limit)
                .fetch();
    }

    /** 고른 행을 {@code until}까지 다시 고르지 않는다(임대, 1회). */
    public long lease(Collection<Long> ids, Instant until) {
        if (ids.isEmpty()) {
            return 0;
        }
        return queryFactory.update(externalBlog)
                .set(externalBlog.nextFetchAt, until)
                .where(externalBlog.id.in(ids), externalBlog.status.eq(ExternalBlogStatus.ACTIVE))
                .execute();
    }

    private static long count(Long value) {
        return value == null ? 0 : value;
    }
}
