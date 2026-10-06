package net.java21.blog.backend.notification.dto;

import java.time.Instant;
import java.util.Map;

import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.notification.repository.NotificationRow;
import net.java21.blog.backend.user.domain.UserStatus;

/**
 * 알림(002 contracts/api.md {@code Notification}). {@code type}·{@code targetType}은 문자열로 준다(이후 스펙이 값을 더하며
 * front는 모르는 값을 공통 문구로 보여준다). 문구는 front가 {@code type}과 {@code params}로 만든다.
 *
 * @param actor 알림을 일으킨 회원. 비회원·시스템이면 null, 탈퇴 회원이면 닉네임·프로필 없이 {@code withdrawn: true}
 * @param blog  관련된 내 블로그. 없으면 null
 */
public record NotificationResponse(long id, String type, Actor actor, BlogRef blog, String targetType,
        Long targetId, Map<String, Object> params, boolean read, Instant createdAt) {

    public record Actor(long userId, String nickname, String profileImageUrl, boolean withdrawn) {
    }

    public record BlogRef(String handle, String title) {
    }

    public static NotificationResponse of(NotificationRow row) {
        return of(row, row.readAt() != null);
    }

    public static NotificationResponse of(NotificationRow row, boolean read) {
        return new NotificationResponse(row.id(), row.type().name(), actorOf(row),
                row.blogHandle() == null ? null : new BlogRef(row.blogHandle(), row.blogTitle()),
                row.targetType() == null ? null : row.targetType().name(), row.targetId(),
                row.params() == null ? Map.of() : row.params(), read, row.createdAt());
    }

    private static Actor actorOf(NotificationRow row) {
        if (row.actorId() == null) {
            return null;
        }
        if (row.actorStatus() == UserStatus.WITHDRAWN) {
            return new Actor(row.actorId(), null, null, true);
        }
        return new Actor(row.actorId(), row.actorNickname(), Media.urlOf(row.actorProfileMediaKey()), false);
    }
}
