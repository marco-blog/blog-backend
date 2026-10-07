package net.java21.blog.backend.guestbook.dto;

/** 비회원 글의 삭제·내용 보기 본문(004 contracts/api.md "설계 규칙과 다르게 만든 것"). 댓글(US3)도 같이 쓴다. */
public record GuestPasswordRequest(String guestPassword) {

    @Override
    public String toString() {
        return "GuestPasswordRequest[guestPassword=****]";
    }
}
