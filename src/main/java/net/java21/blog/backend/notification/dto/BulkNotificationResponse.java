package net.java21.blog.backend.notification.dto;

/** 일괄 작업 결과. {@code updated}: 이번에 읽음으로 바뀐 알림 수(이미 읽은 것은 세지 않음). */
public record BulkNotificationResponse(long updated) {
}
