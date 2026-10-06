package net.java21.blog.backend.admin.portal.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.portal.domain.QPortalCuration.portalCuration;
import static net.java21.blog.backend.portal.domain.QPortalExclusion.portalExclusion;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.admin.portal.CurationStatus;
import net.java21.blog.backend.portal.repository.PortalExposure;
import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 관리자 포털 조회(003 T096, FR-091~093). 목록은 목록 1회 + 수 1회, 노출 가능 여부는 id 목록으로 1회다(N+1 없음).
 */
@Repository
public class CurationQueryRepository {

    private static final QUser admin = new QUser("adminUser");

    private final JPAQueryFactory queryFactory;

    public CurationQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /**
     * 상태별 추천 목록. {@code ACTIVE}·{@code UPCOMING}은 시작·순서 순, {@code ENDED}·전체({@code status} null)는 종료 내림차순.
     */
    public Page<CurationRow> findCurations(CurationStatus status, Instant now, Pageable pageable) {
        BooleanExpression where = statusCondition(status, now);
        List<OrderSpecifier<?>> order = status == CurationStatus.ACTIVE || status == CurationStatus.UPCOMING
                ? List.of(portalCuration.startsAt.asc(), portalCuration.sortOrder.asc(), portalCuration.id.asc())
                : List.of(portalCuration.endsAt.desc(), portalCuration.id.desc());
        List<CurationRow> rows = queryFactory
                .select(Projections.constructor(CurationRow.class, portalCuration.id, post.id, post.title, blog.handle,
                        portalCuration.startsAt, portalCuration.endsAt, portalCuration.sortOrder, admin.id,
                        admin.nickname, portalCuration.createdAt, portalCuration.updatedAt))
                .from(portalCuration)
                .join(portalCuration.post, post)
                .join(post.blog, blog)
                .join(portalCuration.createdBy, admin)
                .where(where)
                .orderBy(order.toArray(OrderSpecifier[]::new))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(portalCuration.count()).from(portalCuration).where(where).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 추천 하나(목록과 같은 모양). */
    public CurationRow findCuration(long id) {
        return queryFactory
                .select(Projections.constructor(CurationRow.class, portalCuration.id, post.id, post.title, blog.handle,
                        portalCuration.startsAt, portalCuration.endsAt, portalCuration.sortOrder, admin.id,
                        admin.nickname, portalCuration.createdAt, portalCuration.updatedAt))
                .from(portalCuration)
                .join(portalCuration.post, post)
                .join(post.blog, blog)
                .join(portalCuration.createdBy, admin)
                .where(portalCuration.id.eq(id))
                .fetchOne();
    }

    /** 기간 {@code [startsAt, endsAt)}과 겹치는 추천 수({@code excludeId}는 빼고 센다, 수정할 때 자기 자신). */
    public long countOverlapping(Instant startsAt, Instant endsAt, Long excludeId) {
        BooleanExpression where = portalCuration.startsAt.lt(endsAt).and(portalCuration.endsAt.gt(startsAt));
        if (excludeId != null) {
            where = where.and(portalCuration.id.ne(excludeId));
        }
        Long count = queryFactory.select(portalCuration.count()).from(portalCuration).where(where).fetchOne();
        return count == null ? 0 : count;
    }

    /** 주어진 글 중 지금 포털 노출 조건을 만족하는 글의 id(쿼리 1회). */
    public Set<Long> findPortalVisiblePostIds(Collection<Long> postIds, PortalCriteria criteria) {
        if (postIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(queryFactory
                .select(post.id)
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(post.id.in(postIds), PortalExposure.portalVisible(criteria))
                .fetch());
    }

    /** 포털 제외 목록(제외 최신순). */
    public Page<ExclusionRow> findExclusions(Pageable pageable) {
        List<ExclusionRow> rows = queryFactory
                .select(Projections.constructor(ExclusionRow.class, post.id, post.title, blog.handle,
                        portalExclusion.reason, admin.id, admin.nickname, portalExclusion.createdAt,
                        portalExclusion.updatedAt))
                .from(portalExclusion)
                .join(portalExclusion.post, post)
                .join(post.blog, blog)
                .join(portalExclusion.excludedBy, admin)
                .orderBy(portalExclusion.createdAt.desc(), portalExclusion.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(portalExclusion.count()).from(portalExclusion).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 글 하나의 제외(없으면 null). */
    public ExclusionRow findExclusion(long postId) {
        return queryFactory
                .select(Projections.constructor(ExclusionRow.class, post.id, post.title, blog.handle,
                        portalExclusion.reason, admin.id, admin.nickname, portalExclusion.createdAt,
                        portalExclusion.updatedAt))
                .from(portalExclusion)
                .join(portalExclusion.post, post)
                .join(post.blog, blog)
                .join(portalExclusion.excludedBy, admin)
                .where(post.id.eq(postId))
                .fetchOne();
    }

    private static BooleanExpression statusCondition(CurationStatus status, Instant now) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case ACTIVE -> portalCuration.startsAt.loe(now).and(portalCuration.endsAt.gt(now));
            case UPCOMING -> portalCuration.startsAt.gt(now);
            case ENDED -> portalCuration.endsAt.loe(now);
        };
    }
}
