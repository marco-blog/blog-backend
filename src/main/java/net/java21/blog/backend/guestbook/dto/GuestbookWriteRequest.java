package net.java21.blog.backend.guestbook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import net.java21.blog.backend.guestbook.domain.GuestbookEntry;

/**
 * 방명록 쓰기(004 contracts/api.md {@code GuestbookWrite}). {@code parentId}가 있으면 블로그 주인의 답글이다. 로그인 회원의
 * {@code guestName}·{@code guestPassword}는 무시한다. 비회원 이름·비밀번호 검증은 {@code GuestAuthorService}가 한다.
 * 005: 비회원은 {@code captchaToken} 필수(FR-141).
 */
public record GuestbookWriteRequest(@NotBlank @Size(max = GuestbookEntry.CONTENT_MAX) String content, Boolean secret,
        Long parentId, String guestName, String guestPassword, String captchaToken) {

    /** 004 호출부용(CAPTCHA 없음). */
    public GuestbookWriteRequest(String content, Boolean secret, Long parentId, String guestName,
            String guestPassword) {
        this(content, secret, parentId, guestName, guestPassword, null);
    }

    @Override
    public String toString() {
        return "GuestbookWriteRequest[secret=" + secret + ", parentId=" + parentId + ", guestName=" + guestName
                + ", guestPassword=****]";
    }
}
