package net.java21.blog.backend.admin.content.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import net.java21.blog.backend.comment.domain.CommentStatus;

/**
 * 콘텐츠 관리 댓글 목록 한 행(006 contracts/api.md {@code AdminCommentRow}). 비회원이면 {@code author} null·{@code guestName},
 * IP는 없다. {@code content}는 앞 200자이고 비밀 댓글은 null(결정 표 8번).
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminCommentRow(long id, long postId, String postTitle, String blogHandle, Long parentId,
        ContentAuthor author, String guestName, boolean secret, String content, CommentStatus status,
        Instant createdAt) {
}
