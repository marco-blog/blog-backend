package net.java21.blog.backend.notification.domain;

/**
 * 알림 종류(notifications.type, 002 data-model). 002는 두 값만 만들고, 이후 스펙(004 BACKUP_READY, 005 REPORT_RESOLVED,
 * 007 EXTERNAL_*)이 자기 값을 더한다(research D3). 문구는 front가 {@code notification:types.{TYPE}}로 만든다.
 */
public enum NotificationType {
    /** 내 글에 새 댓글·답글(내가 쓴 것 제외). target COMMENT, params {@code { postId, postTitle }}. */
    NEW_COMMENT,
    /** 내 블로그의 새 구독자. target BLOG, params {@code { blogTitle }}. */
    NEW_SUBSCRIBER
}
