package net.java21.blog.backend.manage.dto;

import java.time.Instant;

import net.java21.blog.backend.comment.dto.CommentAuthor;

/**
 * 블로그 관리 댓글 목록·대시보드 최근 댓글 한 줄(contracts/api.md {@code Comment + { postId, postTitle }}).
 * 관리 목록은 표시되는 댓글만 담으므로 {@code deleted}는 늘 false이고, 답글도 한 줄씩 나오므로 {@code replies}는 두지 않는다.
 */
public record ManageCommentResponse(Long id, String content, CommentAuthor author, boolean deleted,
        Instant createdAt, Instant updatedAt, Long postId, String postTitle) {
}
