package net.java21.blog.backend.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import net.java21.blog.backend.comment.domain.Comment;

/** 댓글 수정. 내용은 일반 텍스트 1~1000자. */
public record UpdateCommentRequest(@NotBlank @Size(max = Comment.CONTENT_MAX) String content) {
}
