package net.java21.blog.backend.stats.repository;

import static net.java21.blog.backend.post.domain.QPost.post;

import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.stats.dto.VisitStatsResponse;
import org.springframework.stereotype.Repository;

/** 통계 화면의 글 조회(004 FR-067). 주인 화면이라 모든 공개 범위·상태(휴지통 제외). 쿼리 1회. */
@Repository
public class StatsQueryRepository {

    private final JPAQueryFactory queryFactory;

    public StatsQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 조회수 상위 {@code limit}편(같으면 최근 발행·최근 id 먼저). */
    public List<VisitStatsResponse.TopPost> findTopPosts(Long blogId, int limit) {
        return queryFactory
                .select(Projections.constructor(VisitStatsResponse.TopPost.class, post.id, post.title,
                        post.viewCount.longValue()))
                .from(post)
                .where(post.blog.id.eq(blogId), post.status.ne(PostStatus.DELETED))
                .orderBy(post.viewCount.desc(), post.publishedAt.desc().nullsLast(), post.id.desc())
                .limit(limit)
                .fetch();
    }
}
