package net.java21.blog.backend.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import net.java21.blog.backend.comment.domain.Comment;

/** 댓글·답글 쓰기. {@code parentId}가 있으면 그 최상위 댓글의 답글이다. 내용은 일반 텍스트 1~1000자. */
public record CreateCommentRequest(@NotBlank @Size(max = Comment.CONTENT_MAX) String content, Long parentId) {
}
