package net.java21.blog.backend.guestbook.service;

/**
 * 방명록 비밀글 내용을 볼 수 있는지의 한 곳(004 FR-057, research B7). 비밀글(답글이면 부모가 비밀글)은 블로그 주인과 그 최상위 글을
 * 쓴 회원만 본다. 비회원 작성자는 로그인 정체가 없으므로 비밀번호로 여는 {@code unlock}으로만 본다.
 */
public final class GuestbookVisibility {

    private GuestbookVisibility() {
    }

    /**
     * @param secret         최상위 글의 비밀 여부(답글은 부모 값)
     * @param topAuthorId    최상위 글을 쓴 회원 id(비회원이면 null)
     * @param viewerId       보는 회원 id(비로그인 null)
     * @param blogOwnerId    블로그 주인 id
     */
    public static boolean canRead(boolean secret, Long topAuthorId, Long viewerId, Long blogOwnerId) {
        if (!secret) {
            return true;
        }
        if (viewerId == null) {
            return false;
        }
        return viewerId.equals(blogOwnerId) || viewerId.equals(topAuthorId);
    }
}
