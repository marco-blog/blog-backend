package net.java21.blog.backend.comment.service;

/**
 * 비밀 댓글 내용을 볼 수 있는지의 한 곳(004 FR-065, research B6, 결정 표 11번). 목록·관리·사이드바가 같은 규칙을 쓴다.
 * <ul>
 *   <li>비밀 댓글, 비밀 댓글의 답글, 비밀 댓글 아래의 답글은 모두 비밀로 다룬다({@link #isSecret}).</li>
 *   <li>비밀이면 글 주인, 그 댓글을 쓴 회원, (답글이면) 부모 댓글을 쓴 회원만 내용을 본다. 비회원 작성자는 로그인 정체가 없으므로
 *       비밀번호로 여는 {@code POST /comments/{id}/unlock}으로만 본다.</li>
 * </ul>
 */
public final class CommentVisibility {

    private CommentVisibility() {
    }

    /** 답글이면 부모의 비밀 여부도 따른다. */
    public static boolean isSecret(boolean secret, Boolean parentSecret) {
        return secret || Boolean.TRUE.equals(parentSecret);
    }

    /**
     * @param secret         {@link #isSecret}로 정한 비밀 여부
     * @param authorId       이 댓글을 쓴 회원(비회원 null)
     * @param parentAuthorId 답글이면 부모 댓글을 쓴 회원(아니면·비회원 null)
     * @param viewerId       보는 회원(비로그인 null)
     * @param postOwnerId    글 주인
     */
    public static boolean canRead(boolean secret, Long authorId, Long parentAuthorId, Long viewerId,
            Long postOwnerId) {
        if (!secret) {
            return true;
        }
        if (viewerId == null) {
            return false;
        }
        return viewerId.equals(postOwnerId) || viewerId.equals(authorId) || viewerId.equals(parentAuthorId);
    }
}
