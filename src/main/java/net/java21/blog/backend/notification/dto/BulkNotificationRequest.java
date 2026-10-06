package net.java21.blog.backend.notification.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /me/notifications/bulk}(002 contracts/api.md 알림 절). {@code ids}를 생략하면 지금까지의 안 읽은 알림 전부,
 * 있으면 그중 내 알림만(최대 {@value #MAX_IDS}개).
 */
public record BulkNotificationRequest(
        @NotNull NotificationBulkAction action,
        @Size(max = MAX_IDS) List<@NotNull Long> ids) {

    public static final int MAX_IDS = 100;
}
