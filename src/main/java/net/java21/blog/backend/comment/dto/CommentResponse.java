package net.java21.blog.backend.comment.dto;

import java.time.Instant;
import java.util.List;

/**
 * 댓글(contracts/api.md {@code Comment}). 답글이 남은 채 삭제된 댓글은 {@code deleted: true}, {@code content: null}.
 * 답글은 {@code replies}에 작성순으로 들어가고 답글의 {@code replies}는 늘 빈 배열이다(1단계).
 */
public record CommentResponse(Long id, String content, CommentAuthor author, boolean deleted, Instant createdAt,
        Instant updatedAt, List<CommentResponse> replies) {
}
