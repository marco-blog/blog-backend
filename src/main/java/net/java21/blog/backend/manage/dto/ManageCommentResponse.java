package net.java21.blog.backend.manage.dto;

import java.time.Instant;

import net.java21.blog.backend.common.dto.AuthorResponse;

/**
 * 블로그 관리 댓글 목록·대시보드 최근 댓글 한 줄(contracts/api.md {@code Comment + { postId, postTitle }}).
 * 관리 목록은 표시되는 댓글만 담으므로 {@code deleted}는 늘 false이고, 답글도 한 줄씩 나오므로 {@code replies}는 두지 않는다.
 * 004: 주인 화면이라 비밀 댓글도 내용을 주고 {@code secret}으로 표시한다. 비회원은 {@code author.guest: true}.
 */
public record ManageCommentResponse(Long id, String content, AuthorResponse author, boolean deleted, boolean secret,
        Instant createdAt, Instant updatedAt, Long postId, String postTitle) {
}
