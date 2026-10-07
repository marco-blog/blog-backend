package net.java21.blog.backend.portal.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.util.Collection;
import java.util.List;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.portal.service.PortalKey;
import net.java21.blog.backend.portal.service.PortalSourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

/**
 * 주제 페이지 최신순 글(003 FR-078, research P8): 포털 노출 글 중 주어진 소분류의 글, {@code published_at DESC, id DESC}. 카드
 * projection은 {@link PortalCardQueryRepository}와 같고 목록 1회 + 수 1회다. 같은 블로그 2편 제한은 없다(결정 표 11번).
 */
@Repository
public class TopicPostQueryRepository {

    private final JPAQueryFactory queryFactory;

    public TopicPostQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    public Page<PortalCardRow> findLatest(PortalCriteria criteria, Collection<Long> topicIds, Pageable pageable) {
        if (topicIds.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        List<PortalCardRow> content = PortalCardQueryRepository.from(queryFactory, criteria)
                .where(post.topic.id.in(topicIds))
                .orderBy(post.publishedAt.desc(), post.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        return PageableExecutionUtils.getPage(content, pageable, () -> count(criteria, topicIds));
    }

    /** 주제의 포털 노출 글 수(쿼리 1회). */
    public long count(PortalCriteria criteria, Collection<Long> topicIds) {
        if (topicIds.isEmpty()) {
            return 0;
        }
        Long total = queryFactory.select(post.count())
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(PortalExposure.portalVisible(criteria), post.topic.id.in(topicIds))
                .fetchOne();
        return total == null ? 0 : total;
    }

    /**
     * 외부 글과 합치기 위한 가벼운 행(007 research E13): 주제의 포털 노출 글 id·발행 시각 {@code limit}개, 발행 최신순(같으면 id
     * 내림차순). 쿼리 1회.
     */
    public List<PortalKey> findLatestKeys(PortalCriteria criteria, Collection<Long> topicIds, int limit) {
        if (topicIds.isEmpty() || limit <= 0) {
            return List.of();
        }
        return queryFactory.select(post.id, post.publishedAt)
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(PortalExposure.portalVisible(criteria), post.topic.id.in(topicIds))
                .orderBy(post.publishedAt.desc(), post.id.desc())
                .limit(limit)
                .fetch()
                .stream()
                .map(t -> new PortalKey(PortalSourceType.INTERNAL, t.get(post.id), t.get(post.publishedAt)))
                .toList();
    }
}
