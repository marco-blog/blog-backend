package net.java21.blog.backend.guest.dto;

/** 비회원이 쓰는 글의 종류(004 research B6). 속도 한도가 다르다(댓글 1분 5개, 방명록 1분 3개). */
public enum GuestWriteKind {
    COMMENT,
    GUESTBOOK
}
