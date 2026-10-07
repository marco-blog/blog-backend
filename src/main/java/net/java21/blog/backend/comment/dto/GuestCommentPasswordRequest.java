package net.java21.blog.backend.comment.dto;

/** 비회원 댓글 삭제·내용 보기의 비밀번호(004 FR-066). 쿼리 문자열이 아니라 본문으로 받는다(접근 기록에 남지 않게). */
public record GuestCommentPasswordRequest(String guestPassword) {

    /** 로그에 비밀번호가 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "GuestCommentPasswordRequest[guestPassword=****]";
    }
}
