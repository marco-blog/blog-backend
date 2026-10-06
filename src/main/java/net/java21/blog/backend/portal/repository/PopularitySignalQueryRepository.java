package net.java21.blog.backend.portal.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.comment.domain.QComment.comment;
import static net.java21.blog.backend.like.domain.QPostLike.postLike;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.post.domain.QPostDailyStat.postDailyStat;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.portal.service.PortalCriteria;
import org.springframework.stereotype.Repository;

/**
 * 인기 점수 신호(003 FR-086, research P4). 네 신호를 각각 글별 {@code GROUP BY} 쿼리 1회로 읽고, 후보 글의 발행 시각·블로그·주제를
 * 포털 노출 글만 읽는다(후보 1,000개마다 쿼리 1회).
 */
@Repository
public class PopularitySignalQueryRepository {

    static final int CANDIDATE_CHUNK = 1000;

    private final JPAQueryFactory queryFactory;

    public PopularitySignalQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** {@code stat_date >= since}인 일별 통계의 글별 합. @return 글 id → {조회, 끝까지 읽음} */
    public Map<Long, long[]> sumDailyStats(LocalDate since) {
        Map<Long, long[]> sums = new HashMap<>();
        for (Tuple row : queryFactory
                .select(postDailyStat.id.postId, postDailyStat.views.sumLong(), postDailyStat.readCompletes.sumLong())
                .from(postDailyStat)
                .where(postDailyStat.id.statDate.goe(since))
                .groupBy(postDailyStat.id.postId)
                .fetch()) {
            sums.put(row.get(0, Long.class), new long[] {number(row.get(1, Object.class)),
                    number(row.get(2, Object.class))});
        }
        return sums;
    }

    /** {@code created_at >= since}인 좋아요의 글별 수. */
    public Map<Long, Long> countLikes(Instant since) {
        Map<Long, Long> counts = new HashMap<>();
        for (Tuple row : queryFactory
                .select(postLike.id.postId, postLike.count())
                .from(postLike)
                .where(postLike.createdAt.goe(since))
                .groupBy(postLike.id.postId)
                .fetch()) {
            counts.put(row.get(postLike.id.postId), row.get(postLike.count()));
        }
        return counts;
    }

    /** {@code created_at >= since}이고 삭제·숨김이 아닌 댓글의 글별 수. */
    public Map<Long, Long> countComments(Instant since) {
        Map<Long, Long> counts = new HashMap<>();
        for (Tuple row : queryFactory
                .select(comment.post.id, comment.count())
                .from(comment)
                .where(comment.createdAt.goe(since), comment.status.eq(CommentStatus.ACTIVE))
                .groupBy(comment.post.id)
                .fetch()) {
            counts.put(row.get(comment.post.id), row.get(comment.count()));
        }
        return counts;
    }

    /** 신호가 있는 글 중 포털 노출 글의 발행 시각·블로그·주제. */
    public List<PopularityCandidateRow> findCandidates(PortalCriteria criteria, Collection<Long> postIds) {
        List<Long> ids = List.copyOf(postIds);
        List<PopularityCandidateRow> rows = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += CANDIDATE_CHUNK) {
            List<Long> chunk = ids.subList(from, Math.min(ids.size(), from + CANDIDATE_CHUNK));
            rows.addAll(queryFactory
                    .select(Projections.constructor(PopularityCandidateRow.class, post.id, blog.id, post.topic.id,
                            post.publishedAt))
                    .from(post)
                    .join(post.blog, blog)
                    .join(blog.user, user)
                    .where(PortalExposure.portalVisible(criteria), post.id.in(chunk))
                    .fetch());
        }
        return rows;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
