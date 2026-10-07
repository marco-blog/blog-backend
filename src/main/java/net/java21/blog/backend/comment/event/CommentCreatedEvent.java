package net.java21.blog.backend.comment.event;

/**
 * 댓글·답글이 저장됐다(002 research D3). 커밋 뒤 글이 속한 블로그의 주인에게 NEW_COMMENT 알림을 만든다(작성자가 주인이면 만들지 않음).
 *
 * @param commentId   새 댓글
 * @param postId      댓글이 달린 글
 * @param postTitle   글 제목(알림 문구용, 만들 때의 값)
 * @param blogId      글이 속한 블로그
 * @param blogOwnerId 블로그 주인(알림 받는 회원)
 * @param authorId    댓글 작성 회원. 비회원 댓글(004)이면 null
 * @param guestName   비회원 댓글의 이름(004). 회원 댓글이면 null
 */
public record CommentCreatedEvent(long commentId, long postId, String postTitle, long blogId, long blogOwnerId,
        Long authorId, String guestName) {

    public CommentCreatedEvent(long commentId, long postId, String postTitle, long blogId, long blogOwnerId,
            long authorId) {
        this(commentId, postId, postTitle, blogId, blogOwnerId, Long.valueOf(authorId), null);
    }
}
