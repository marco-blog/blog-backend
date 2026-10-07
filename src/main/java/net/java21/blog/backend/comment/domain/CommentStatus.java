package net.java21.blog.backend.comment.domain;

/**
 * 댓글 상태(data-model comments). HIDDEN(005 FR-041)은 관리자가 숨긴 댓글로, 작성 회원에게만 내용이 보인다.
 * DELETED는 답글이 남은 최상위 댓글을 지웠을 때 "삭제된 댓글입니다" 자리로만 남는 상태다(답글이 없으면 행을 지운다).
 */
public enum CommentStatus {
    ACTIVE,
    DELETED,
    HIDDEN
}
