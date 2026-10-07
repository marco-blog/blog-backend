package net.java21.blog.backend.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import net.java21.blog.backend.comment.domain.Comment;

/**
 * 댓글 수정. 내용은 일반 텍스트 1~1000자. 004: {@code secret}(null이면 그대로), 비회원 댓글은 {@code guestPassword} 필수.
 */
public record UpdateCommentRequest(@NotBlank @Size(max = Comment.CONTENT_MAX) String content, Boolean secret,
        String guestPassword) {

    public UpdateCommentRequest(String content) {
        this(content, null, null);
    }

    /** 로그에 비밀번호가 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "UpdateCommentRequest[secret=" + secret + ", guestPassword="
                + (guestPassword == null ? null : "****") + "]";
    }
}
