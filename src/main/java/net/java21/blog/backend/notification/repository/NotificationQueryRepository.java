package net.java21.blog.backend.notification.repository;

import static net.java21.blog.backend.notification.domain.QNotification.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.QBlog;
import net.java21.blog.backend.media.domain.QMedia;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.user.domain.QUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

/**
 * 알림 조회·읽음·정리(002 FR-033, data-model notifications). 목록은 DTO projection으로 일으킨 회원·블로그를 함께 읽어
 * 알림 수와 무관하게 쿼리 2회(목록, 전체 수). 쓰기는 모두 집합 UPDATE·DELETE 1회다.
 */
@Repository
public class NotificationQueryRepository {

    private static final QUser actor = new QUser("actor");
    private static final QMedia actorMedia = new QMedia("actorMedia");
    private static final QBlog notificationBlog = new QBlog("notificationBlog");

    private final JPAQueryFactory queryFactory;

    public NotificationQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** 내 알림, 최신순(같은 시각은 id 내림차순). */
    public Page<NotificationRow> findMine(long userId, Pageable pageable) {
        List<NotificationRow> rows = rows()
                .where(notification.user.id.eq(userId))
                .orderBy(notification.createdAt.desc(), notification.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
        Long total = queryFactory.select(notification.count())
                .from(notification)
                .where(notification.user.id.eq(userId))
                .fetchOne();
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    /** 내 알림 하나. 다른 회원의 알림이면 빈 값. 쿼리 1회. */
    public Optional<NotificationRow> findMine(long userId, long notificationId) {
        return Optional.ofNullable(rows()
                .where(notification.id.eq(notificationId), notification.user.id.eq(userId))
                .fetchOne());
    }

    /** 안 읽은 알림 수. 쿼리 1회. */
    public long countUnread(long userId) {
        Long count = queryFactory.select(notification.count())
                .from(notification)
                .where(notification.user.id.eq(userId), notification.readAt.isNull())
                .fetchOne();
        return count == null ? 0 : count;
    }

    /**
     * 내 안 읽은 알림을 읽음으로. {@code ids}가 null이면 지금까지의 안 읽은 알림 전부, 있으면 그중 내 알림만. 이미 읽은 것은 그대로다.
     *
     * @return 읽음으로 바꾼 수
     */
    public long markRead(long userId, Collection<Long> ids, Instant now) {
        BooleanExpression where = notification.user.id.eq(userId).and(notification.readAt.isNull());
        if (ids != null) {
            if (ids.isEmpty()) {
                return 0;
            }
            where = where.and(notification.id.in(ids));
        }
        return queryFactory.update(notification)
                .set(notification.readAt, now)
                .where(where)
                .execute();
    }

    /** 같은 받는 회원·일으킨 회원·블로그의 NEW_SUBSCRIBER 알림이 {@code since} 이후에 있는지(중복 방지, 결정 6). 쿼리 1회. */
    public boolean existsSubscriberNotificationSince(long userId, long actorId, long blogId, Instant since) {
        return queryFactory.selectOne()
                .from(notification)
                .where(notification.user.id.eq(userId), notification.createdAt.goe(since),
                        notification.type.eq(NotificationType.NEW_SUBSCRIBER),
                        notification.actor.id.eq(actorId), notification.blog.id.eq(blogId))
                .fetchFirst() != null;
    }

    /** {@code created_at < cutoff}인 알림 id(오래된 순, 최대 {@code limit}개). */
    public List<Long> findIdsCreatedBefore(Instant cutoff, int limit) {
        return queryFactory.select(notification.id)
                .from(notification)
                .where(notification.createdAt.lt(cutoff))
                .orderBy(notification.createdAt.asc(), notification.id.asc())
                .limit(limit)
                .fetch();
    }

    public long deleteByIds(List<Long> ids) {
        return ids.isEmpty() ? 0 : queryFactory.delete(notification).where(notification.id.in(ids)).execute();
    }

    /** 회원들이 받은 알림을 지운다(개인정보 파기). */
    public long deleteByUserIds(Collection<Long> userIds) {
        return userIds.isEmpty() ? 0
                : queryFactory.delete(notification).where(notification.user.id.in(userIds)).execute();
    }

    private JPAQuery<NotificationRow> rows() {
        return queryFactory
                .select(Projections.constructor(NotificationRow.class,
                        notification.id, notification.type, actor.id, actor.nickname, actor.status,
                        actorMedia.mediaKey, notificationBlog.handle, notificationBlog.title, notification.targetType,
                        notification.targetId, notification.params, notification.readAt, notification.createdAt))
                .from(notification)
                .leftJoin(notification.actor, actor)
                .leftJoin(actor.profileMedia, actorMedia)
                .leftJoin(notification.blog, notificationBlog);
    }
}
