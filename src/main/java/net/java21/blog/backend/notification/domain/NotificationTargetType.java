package net.java21.blog.backend.notification.domain;

/** 알림 대상 종류(notifications.target_type). 002는 두 값만 쓴다(BLOG_EXPORT·EXTERNAL_BLOG·REPORT는 이후 스펙). */
public enum NotificationTargetType {
    COMMENT,
    BLOG
}
