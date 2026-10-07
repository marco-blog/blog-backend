package net.java21.blog.backend.guestbook.domain;

/**
 * 방명록 글 상태(004 data-model guestbook_entries). 005가 HIDDEN(관리자 숨김)을 더한다.
 * DELETED는 답글이 남은 글을 지웠을 때 "삭제된 글입니다" 자리로만 남는 상태다(답글이 없으면 행을 지운다).
 */
public enum GuestbookStatus {
    ACTIVE,
    DELETED
}
