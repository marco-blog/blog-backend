package net.java21.blog.backend.export.event;

import java.time.Instant;

/**
 * 백업이 준비됨(004 FR-145). 커밋 뒤 요청한 주인에게 {@code BACKUP_READY} 알림(target {@code BLOG_EXPORT}, params
 * {@code { blogTitle, handle, expiresAt }})을 만든다.
 */
public record BlogExportReadyEvent(long exportId, long requestedBy, long blogId, String blogTitle, String handle,
        Instant expiresAt) {
}
