package net.java21.blog.backend.notification.domain;

/** 알림 대상 종류(notifications.target_type). 002는 COMMENT·BLOG, 004가 BLOG_EXPORT를 더한다(EXTERNAL_BLOG·REPORT는 이후 스펙). */
public enum NotificationTargetType {
    COMMENT,
    BLOG,
    /** 004 블로그 백업(blog_exports.id). */
    BLOG_EXPORT
}
