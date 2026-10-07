package net.java21.blog.backend.admin.content.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import net.java21.blog.backend.guestbook.domain.GuestbookStatus;

/** 콘텐츠 관리 방명록 목록 한 행(006 contracts/api.md {@code AdminGuestbookRow}). 규칙은 {@link AdminCommentRow}와 같다. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminGuestbookRow(long id, String blogHandle, Long parentId, ContentAuthor author, String guestName,
        boolean secret, String content, GuestbookStatus status, Instant createdAt) {
}
