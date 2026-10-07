package net.java21.blog.backend.notification.domain;

/**
 * 알림 종류(notifications.type, 002 data-model). 002는 두 값만 만들고, 이후 스펙(004 BACKUP_READY, 005 REPORT_RESOLVED,
 * 007 EXTERNAL_*)이 자기 값을 더한다(research D3). 문구는 front가 {@code notification:types.{TYPE}}로 만든다.
 */
public enum NotificationType {
    /** 내 글에 새 댓글·답글(내가 쓴 것 제외). target COMMENT, params {@code { postId, postTitle }}. */
    NEW_COMMENT,
    /** 내 블로그의 새 구독자. target BLOG, params {@code { blogTitle }}. */
    NEW_SUBSCRIBER,
    /** 004 블로그 백업 준비됨(요청한 주인에게, 행위자 없음). target BLOG_EXPORT, params {@code { blogTitle, handle, expiresAt }}. */
    BACKUP_READY,
    /**
     * 005 내 신고 처리 결과(회원 신고자에게, 행위자 없음, 링크 없음). target REPORT, params {@code { targetType, decision }}
     * ({@code decision}: ACTIONED·DISMISSED, 대상 제목·내용 없음).
     */
    REPORT_RESOLVED,
    /** 007 외부 블로그 신청 승인(신청 회원에게, 행위자 없음). target EXTERNAL_BLOG, params {@code { externalBlogTitle }}. */
    EXTERNAL_BLOG_APPROVED,
    /** 007 외부 블로그 신청 거절. target EXTERNAL_BLOG, params {@code { externalBlogTitle, reason }}. */
    EXTERNAL_BLOG_REJECTED,
    /** 007 연속 실패 자동 중지(관리 회원에게). target EXTERNAL_BLOG, params {@code { externalBlogTitle, lastResult }}. */
    EXTERNAL_FEED_STOPPED
}
