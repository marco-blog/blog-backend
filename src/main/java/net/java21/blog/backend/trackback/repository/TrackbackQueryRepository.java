package net.java21.blog.backend.trackback.repository;

import static net.java21.blog.backend.trackback.domain.QTrackback.trackback;
import static net.java21.blog.backend.trackback.domain.QTrackbackPingLog.trackbackPingLog;

import java.util.EnumSet;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.QBlog;
import net.java21.blog.backend.post.domain.QPost;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import net.java21.blog.backend.trackback.domain.TrackbackStatus;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 트랙백 목록(005 FR-049·051, research M14).
 * <ul>
 *   <li>글 상세 목록 {@link #findVisible}: ACTIVE, 최신순. 서비스 안 출처 글은 본문 노출 가능일 때만(출처 글·블로그·작성자를 별칭으로
 *       LEFT JOIN한 {@link PostExposure#bodyVisible(QPost, QBlog, QUser)}). 쿼리 2회(목록 + 개수).</li>
 *   <li>블로그 관리 목록 {@link #findManaged}: 그 블로그 글들이 받은 ACTIVE·HIDDEN, 받은 글 제목 포함. 쿼리 2회.</li>
 *   <li>보낸 기록 {@link #findRecentPings}: 최신 N개. 쿼리 1회.</li>
 * </ul>
 */
@Repository
public class TrackbackQueryRepository {

    private static final EnumSet<TrackbackStatus> MANAGED = EnumSet.of(TrackbackStatus.ACTIVE, TrackbackStatus.HIDDEN);

    private final JPAQueryFactory queryFactory;

    public TrackbackQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 글 상세에 보이는 트랙백(ACTIVE, 출처 노출 조건). */
    public Page<TrackbackRow> findVisible(Long postId, Pageable pageable) {
        Aliases a = new Aliases();
        BooleanExpression condition = trackback.post.id.eq(postId).and(visible(a));
        List<TrackbackRow> rows = withSource(queryFactory.select(row(a)).from(trackback), a)
                .join(trackback.post, a.post)
                .where(condition)
                .orderBy(trackback.createdAt.desc(), trackback.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = withSource(queryFactory.select(trackback.count()).from(trackback), a).where(condition)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 글 상세의 {@code trackbackCount}(목록과 같은 조건). 쿼리 1회. */
    public long countVisible(Long postId) {
        Aliases a = new Aliases();
        Long total = withSource(queryFactory.select(trackback.count()).from(trackback), a)
                .where(trackback.post.id.eq(postId).and(visible(a)))
                .fetchOne();
        return total == null ? 0 : total;
    }

    /** 이 블로그 글들이 받은 트랙백(ACTIVE·HIDDEN, 최신순, 받은 글 제목). */
    public Page<TrackbackRow> findManaged(Long blogId, Pageable pageable) {
        Aliases a = new Aliases();
        BooleanExpression condition = a.post.blog.id.eq(blogId).and(trackback.status.in(MANAGED));
        List<TrackbackRow> rows = queryFactory.select(row(a)).from(trackback)
                .join(trackback.post, a.post)
                .leftJoin(trackback.sourcePost, a.source)
                .where(condition)
                .orderBy(trackback.createdAt.desc(), trackback.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(trackback.count()).from(trackback)
                .join(trackback.post, a.post)
                .where(condition)
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 이 글에서 보낸 트랙백 기록 최신 {@code limit}개. */
    public List<TrackbackPingLog> findRecentPings(Long postId, int limit) {
        return queryFactory.selectFrom(trackbackPingLog)
                .where(trackbackPingLog.post.id.eq(postId))
                .orderBy(trackbackPingLog.createdAt.desc(), trackbackPingLog.id.desc())
                .limit(limit)
                .fetch();
    }

    /** 받은 글({@code post})과 출처 글·블로그·작성자({@code source}·{@code sourceBlog}·{@code sourceUser}) 별칭. */
    private static final class Aliases {
        final QPost post = new QPost("targetPost");
        final QPost source = new QPost("sourcePost");
        final QBlog sourceBlog = new QBlog("sourceBlog");
        final QUser sourceUser = new QUser("sourceUser");
    }

    private static <T> JPAQuery<T> withSource(JPAQuery<T> query, Aliases a) {
        return query.leftJoin(trackback.sourcePost, a.source)
                .leftJoin(a.source.blog, a.sourceBlog)
                .leftJoin(a.sourceBlog.user, a.sourceUser);
    }

    private static BooleanExpression visible(Aliases a) {
        return trackback.status.eq(TrackbackStatus.ACTIVE)
                .and(a.source.id.isNull().or(PostExposure.bodyVisible(a.source, a.sourceBlog, a.sourceUser)));
    }

    private static com.querydsl.core.types.Expression<TrackbackRow> row(Aliases a) {
        return Projections.constructor(TrackbackRow.class, trackback.id, trackback.title, trackback.excerpt,
                trackback.blogName, trackback.sourceUrl, trackback.createdAt, a.source.id, trackback.status,
                a.post.id, a.post.title);
    }
}
