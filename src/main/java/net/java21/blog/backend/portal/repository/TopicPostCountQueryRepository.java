package net.java21.blog.backend.portal.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.querydsl.core.Tuple;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.portal.service.PortalCriteria;
import org.springframework.stereotype.Repository;

/**
 * 주제별 최근 포털 노출 글 수(003 FR-147 자동 숨김, research P2). {@link PortalExposure} + {@code published_at >= since}를
 * 소분류별 {@code GROUP BY} 한 번(쿼리 1회)으로 센다. 주제 없는 글은 세지 않는다.
 */
@Repository
public class TopicPostCountQueryRepository {

    private final JPAQueryFactory queryFactory;

    public TopicPostCountQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** @return 주제(소분류) id → 글 수. 글이 없는 주제는 빠진다 */
    public Map<Long, Long> countRecentByTopic(PortalCriteria criteria, Instant since) {
        Map<Long, Long> counts = new HashMap<>();
        for (Tuple row : queryFactory
                .select(post.topic.id, post.count())
                .from(post)
                .join(post.blog, blog)
                .join(blog.user, user)
                .where(PortalExposure.portalVisible(criteria), post.topic.id.isNotNull(), post.publishedAt.goe(since))
                .groupBy(post.topic.id)
                .fetch()) {
            counts.put(row.get(post.topic.id), row.get(post.count()));
        }
        return counts;
    }
}
