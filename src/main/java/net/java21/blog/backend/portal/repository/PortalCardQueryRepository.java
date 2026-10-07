package net.java21.blog.backend.portal.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.querydsl.core.types.ConstructorExpression;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.media.domain.QMedia;
import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.portal.service.PortalCursor;
import net.java21.blog.backend.portal.service.PortalSourceType;
import org.springframework.stereotype.Repository;

/**
 * 포털 카드 조회(003 FR-085, research P6·P7). 모두 {@link PortalExposure} 조각을 쓰며, 블로그·작성자·프로필 이미지를 한 쿼리의
 * DTO projection으로 읽으므로 쿼리 수가 카드 수와 무관하다(1회).
 */
@Repository
public class PortalCardQueryRepository {

    static final QMedia authorMedia = new QMedia("authorMedia");

    private final JPAQueryFactory queryFactory;

    public PortalCardQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 카드 projection. 쿼리는 {@link #from(JPAQueryFactory, PortalCriteria)}의 조인을 써야 한다. */
    static ConstructorExpression<PortalCardRow> card() {
        return Projections.constructor(PortalCardRow.class, post.id, post.title, post.summary, post.thumbnailUrl,
                post.topic.id, blog.id, blog.handle, blog.title, user.nickname, authorMedia.mediaKey, post.publishedAt,
                post.likeCount, post.commentCount);
    }

    /** 포털 노출 글 카드 쿼리의 시작(post + blog + user + 작성자 프로필 이미지). */
    static JPAQuery<PortalCardRow> from(JPAQueryFactory queryFactory, PortalCriteria criteria) {
        return queryFactory.select(card())
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .leftJoin(user.profileMedia, authorMedia)
                .where(PortalExposure.portalVisible(criteria));
    }

    /** id 목록의 카드를 그 순서대로. 포털 노출이 아닌 글은 빠진다. */
    public List<PortalCardRow> findByIds(List<Long> ids, PortalCriteria criteria) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            order.putIfAbsent(ids.get(i), i);
        }
        return from(queryFactory, criteria)
                .where(post.id.in(order.keySet()))
                .fetch()
                .stream()
                .sorted(Comparator.comparingInt(row -> order.get(row.id())))
                .toList();
    }

    /** 발행 최신순(같은 시각은 id 내림차순) 카드 {@code limit}개. {@code after}가 있으면 그 행 다음부터. */
    public List<PortalCardRow> findLatest(PortalCriteria criteria, PortalCursor.Position after, int limit) {
        return from(queryFactory, criteria)
                .where(after(after))
                .orderBy(post.publishedAt.desc(), post.id.desc())
                .limit(limit)
                .fetch();
    }

    private static BooleanExpression after(PortalCursor.Position position) {
        if (position == null) {
            return null;
        }
        Objects.requireNonNull(position.publishedAt());
        if (position.source() != PortalSourceType.INTERNAL) {
            // 같은 시각이면 내부 글이 외부 글보다 앞이므로 외부 글 위치 다음의 내부 글은 더 이른 시각뿐이다(007 research E13)
            return post.publishedAt.lt(position.publishedAt());
        }
        return post.publishedAt.lt(position.publishedAt())
                .or(post.publishedAt.eq(position.publishedAt()).and(post.id.lt(position.id())));
    }
}
