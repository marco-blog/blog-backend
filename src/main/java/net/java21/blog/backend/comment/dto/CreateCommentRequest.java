package net.java21.blog.backend.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import net.java21.blog.backend.comment.domain.Comment;

/**
 * 댓글·답글 쓰기. {@code parentId}가 있으면 그 최상위 댓글의 답글이다. 내용은 일반 텍스트 1~1000자.
 * 004: {@code secret}(비밀 댓글, 기본 false), 비로그인 요청은 비회원 댓글이며 {@code guestName}·{@code guestPassword}가 필요하다
 * (검증은 {@code GuestAuthorService}). 로그인 회원이 보낸 비회원 필드는 무시한다. 005: 비회원은 {@code captchaToken} 필수(FR-141).
 */
public record CreateCommentRequest(@NotBlank @Size(max = Comment.CONTENT_MAX) String content, Long parentId,
        Boolean secret, String guestName, String guestPassword, String captchaToken) {

    public CreateCommentRequest(String content, Long parentId) {
        this(content, parentId, null, null, null);
    }

    /** 004 호출부용(CAPTCHA 없음). */
    public CreateCommentRequest(String content, Long parentId, Boolean secret, String guestName,
            String guestPassword) {
        this(content, parentId, secret, guestName, guestPassword, null);
    }

    /** 로그에 비밀번호가 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "CreateCommentRequest[parentId=" + parentId + ", secret=" + secret + ", guestName=" + guestName
                + ", guestPassword=" + (guestPassword == null ? null : "****") + "]";
    }
}
