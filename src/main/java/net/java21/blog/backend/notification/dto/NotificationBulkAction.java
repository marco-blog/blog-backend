package net.java21.blog.backend.notification.dto;

/** 알림 일괄 작업. 002는 읽음 처리만 둔다(모르는 값은 400 {@code VALIDATION_FAILED}). */
public enum NotificationBulkAction {
    MARK_READ
}
