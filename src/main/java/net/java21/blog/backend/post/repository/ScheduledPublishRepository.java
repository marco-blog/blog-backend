package net.java21.blog.backend.post.repository;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.post.domain.QPost.post;

import java.time.Instant;
import java.util.List;

import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.post.domain.PostStatus;
import org.springframework.stereotype.Repository;

/**
 * 예약 발행 작업의 조회·조건부 UPDATE(004 FR-064, research B5). 잠금 없이 조건부 UPDATE로 이중 발행을 막는다:
 * 주인이 그 사이에 예약을 취소·수정했거나 다른 실행이 먼저 발행했으면 0행이 바뀐다.
 */
@Repository
public class ScheduledPublishRepository {

    private final JPAQueryFactory queryFactory;

    public ScheduledPublishRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 예약 시각이 지난 예약 글 id, 예약 시각 순 최대 {@code limit}개({@code idx_posts_status_scheduled}). 쿼리 1회. */
    public List<Long> findDueIds(Instant now, int limit) {
        return queryFactory.select(post.id)
                .from(post)
                .where(post.status.eq(PostStatus.SCHEDULED), post.scheduledAt.loe(now))
                .orderBy(post.scheduledAt.asc(), post.id.asc())
                .limit(limit)
                .fetch();
    }

    /**
     * 아직 예약 상태이고 시각이 지났으면 발행한다: PUBLISHED, {@code published_at} = 실제 발행 시각(이미 있으면 유지),
     * {@code scheduled_at} = NULL. 바뀐 행 수(1 또는 0). 쿼리 1회.
     */
    public long publishIfDue(Long postId, Instant now) {
        return queryFactory.update(post)
                .set(post.status, PostStatus.PUBLISHED)
                .set(post.publishedAt, post.publishedAt.coalesce(now))
                .setNull(post.scheduledAt)
                .where(post.id.eq(postId), post.status.eq(PostStatus.SCHEDULED), post.scheduledAt.loe(now))
                .execute();
    }

    /** 그 글이 속한 블로그의 첫 발행 시각이 비어 있으면 채운다(003 FR-087과 같은 규칙). 쿼리 1회. */
    public long markBlogFirstPublished(Long postId, Instant now) {
        return queryFactory.update(blog)
                .set(blog.firstPublishedAt, now)
                .where(blog.firstPublishedAt.isNull(),
                        blog.id.eq(JPAExpressions.select(post.blog.id).from(post).where(post.id.eq(postId))))
                .execute();
    }
}
