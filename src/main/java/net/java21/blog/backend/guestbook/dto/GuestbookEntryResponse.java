package net.java21.blog.backend.guestbook.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.dto.AuthorResponse;

/**
 * 방명록 글(004 contracts/api.md {@code GuestbookEntry}). 볼 수 없는 비밀글은 {@code content} null, 삭제 자리는
 * {@code deleted: true}·{@code content}·{@code author} null. 답글은 {@code replies}에 작성순(답글의 {@code replies}는 빈 배열).
 * 005: 관리자가 숨긴 글은 {@code hidden: true}. 작성 회원에게는 내용과 함께, 다른 사람에게는 보이는 답글이 있을 때만
 * {@code content}·{@code author} null인 자리로 나온다.
 */
public record GuestbookEntryResponse(Long id, String content, boolean secret, boolean deleted, AuthorResponse author,
        Instant createdAt, Instant updatedAt, List<GuestbookEntryResponse> replies, boolean hidden) {

    /** 숨기지 않은 글. */
    public GuestbookEntryResponse(Long id, String content, boolean secret, boolean deleted, AuthorResponse author,
            Instant createdAt, Instant updatedAt, List<GuestbookEntryResponse> replies) {
        this(id, content, secret, deleted, author, createdAt, updatedAt, replies, false);
    }
}
