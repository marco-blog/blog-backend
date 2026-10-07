package net.java21.blog.backend.guestbook.repository;

import static net.java21.blog.backend.guestbook.domain.QGuestbookEntry.guestbookEntry;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.guestbook.domain.QGuestbookEntry;
import net.java21.blog.backend.media.domain.QMedia;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 방명록 조회(004 T042, QueryDSL DTO projection). 작성자는 LEFT JOIN으로 읽어(비회원 행 포함) 글 수와 관계없이 쿼리 수가 일정하다.
 * <ul>
 *   <li>목록: 최상위 글 페이지(쿼리 1회 + 전체 수 1회) + 그 글들의 답글을 한 번에(1회).</li>
 *   <li>대시보드: 최근 7일 최상위 글 수(1회), 최근 글(1회, 답글 제외).</li>
 * </ul>
 */
@Repository
public class GuestbookQueryRepository {

    private static final QUser author = new QUser("author");
    private static final QMedia authorMedia = new QMedia("authorMedia");
    private static final QGuestbookEntry reply = new QGuestbookEntry("reply");

    private final JPAQueryFactory queryFactory;

    public GuestbookQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 최상위 글(ACTIVE와 답글이 남은 삭제 자리), 최신순 페이지. */
    public Page<GuestbookRow> findPage(Long blogId, Pageable pageable) {
        BooleanExpression where = topLevel(blogId).and(guestbookEntry.status.eq(GuestbookStatus.ACTIVE)
                .or(JPAExpressions.selectOne().from(reply).where(reply.parent.id.eq(guestbookEntry.id)).exists()));
        List<GuestbookRow> rows = select()
                .where(where)
                .orderBy(guestbookEntry.createdAt.desc(), guestbookEntry.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(guestbookEntry.count()).from(guestbookEntry).where(where).fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 이 글들의 답글, 작성순. 쿼리 1회. */
    public List<GuestbookRow> findReplies(Collection<Long> parentIds) {
        if (parentIds.isEmpty()) {
            return List.of();
        }
        return select()
                .where(guestbookEntry.parent.id.in(parentIds), guestbookEntry.status.eq(GuestbookStatus.ACTIVE))
                .orderBy(guestbookEntry.createdAt.asc(), guestbookEntry.id.asc())
                .fetch();
    }

    /** {@code since} 이후 남긴 최상위 글 수(대시보드 "최근 7일 새 방명록"). */
    public long countRecent(Long blogId, Instant since) {
        Long count = queryFactory.select(guestbookEntry.count()).from(guestbookEntry)
                .where(topLevel(blogId), guestbookEntry.status.eq(GuestbookStatus.ACTIVE),
                        guestbookEntry.createdAt.goe(since))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** 최근 최상위 글 {@code limit}건(답글 제외). */
    public List<GuestbookRow> findRecent(Long blogId, int limit) {
        return select()
                .where(topLevel(blogId), guestbookEntry.status.eq(GuestbookStatus.ACTIVE))
                .orderBy(guestbookEntry.createdAt.desc(), guestbookEntry.id.desc())
                .limit(limit)
                .fetch();
    }

    private JPAQuery<GuestbookRow> select() {
        return queryFactory
                .select(Projections.constructor(GuestbookRow.class, guestbookEntry.id, guestbookEntry.parent.id,
                        guestbookEntry.content, guestbookEntry.secret, guestbookEntry.status, author.id,
                        author.nickname, authorMedia.mediaKey, guestbookEntry.guestName, guestbookEntry.createdAt,
                        guestbookEntry.updatedAt))
                .from(guestbookEntry)
                .leftJoin(guestbookEntry.user, author)
                .leftJoin(author.profileMedia, authorMedia);
    }

    private static BooleanExpression topLevel(Long blogId) {
        return guestbookEntry.blog.id.eq(blogId).and(guestbookEntry.parent.isNull());
    }
}
