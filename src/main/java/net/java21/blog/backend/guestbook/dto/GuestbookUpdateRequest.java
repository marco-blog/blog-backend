package net.java21.blog.backend.guestbook.dto;

import jakarta.validation.constraints.Size;

import net.java21.blog.backend.guestbook.domain.GuestbookEntry;

/** 방명록 글 수정. 보내지 않은 값은 그대로. 비회원 글은 {@code guestPassword}가 필요하다. */
public record GuestbookUpdateRequest(@Size(max = GuestbookEntry.CONTENT_MAX) String content, Boolean secret,
        String guestPassword) {

    @Override
    public String toString() {
        return "GuestbookUpdateRequest[secret=" + secret + ", guestPassword=****]";
    }
}
