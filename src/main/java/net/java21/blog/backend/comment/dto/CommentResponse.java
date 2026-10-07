package net.java21.blog.backend.comment.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.dto.AuthorResponse;

/**
 * 댓글(contracts/api.md {@code Comment}). 답글이 남은 채 삭제된 댓글은 {@code deleted: true}, {@code content: null}.
 * 답글은 {@code replies}에 작성순으로 들어가고 답글의 {@code replies}는 늘 빈 배열이다(1단계).
 * 004: {@code secret}(비밀 댓글, 답글은 부모를 따름)이고 볼 수 없는 사람에게는 {@code content: null}. 비회원 작성자는 {@code author.guest: true}.
 */
public record CommentResponse(Long id, String content, AuthorResponse author, boolean deleted, boolean secret,
        Instant createdAt, Instant updatedAt, List<CommentResponse> replies) {
}
