package net.java21.blog.backend.notification.domain;

/** 알림 대상 종류(notifications.target_type). 002는 COMMENT·BLOG, 004가 BLOG_EXPORT를 더한다(005가 REPORT, EXTERNAL_BLOG는 007). */
public enum NotificationTargetType {
    COMMENT,
    BLOG,
    /** 004 블로그 백업(blog_exports.id). */
    BLOG_EXPORT,
    /** 005 신고(reports.id). */
    REPORT,
    /** 007 외부 블로그(external_blogs.id). front 링크 {@code /manage/external-blogs/{id}}. */
    EXTERNAL_BLOG
}
