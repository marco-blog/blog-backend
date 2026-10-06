package net.java21.blog.backend.notification.repository;

import java.time.Instant;
import java.util.Map;

import net.java21.blog.backend.notification.domain.NotificationTargetType;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.user.domain.UserStatus;

/**
 * 알림 목록 한 줄(DTO projection): 알림 + 일으킨 회원(id·닉네임·상태·프로필 이미지 키, 없으면 null) + 관련 블로그(handle·title, 없으면 null).
 */
public record NotificationRow(Long id, NotificationType type, Long actorId, String actorNickname,
        UserStatus actorStatus, String actorProfileMediaKey, String blogHandle, String blogTitle,
        NotificationTargetType targetType, Long targetId, Map<String, Object> params, Instant readAt,
        Instant createdAt) {
}
