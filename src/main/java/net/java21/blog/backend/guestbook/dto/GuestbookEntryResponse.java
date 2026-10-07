package net.java21.blog.backend.guestbook.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.dto.AuthorResponse;

/**
 * 방명록 글(004 contracts/api.md {@code GuestbookEntry}). 볼 수 없는 비밀글은 {@code content} null, 삭제 자리는
 * {@code deleted: true}·{@code content}·{@code author} null. 답글은 {@code replies}에 작성순(답글의 {@code replies}는 빈 배열).
 */
public record GuestbookEntryResponse(Long id, String content, boolean secret, boolean deleted, AuthorResponse author,
        Instant createdAt, Instant updatedAt, List<GuestbookEntryResponse> replies) {
}
