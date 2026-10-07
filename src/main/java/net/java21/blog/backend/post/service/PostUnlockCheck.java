package net.java21.blog.backend.post.service;

import net.java21.blog.backend.post.domain.Post;

/**
 * 이 요청이 보호 글을 비밀번호로 연 적이 있는지(004 FR-062, research B4). 컨트롤러가 요청 쿠키로 만들어
 * ({@link PostUnlockCookies#checker}) 글 상세·댓글·조회수 서비스에 넘긴다.
 */
@FunctionalInterface
public interface PostUnlockCheck {

    /** 열람 쿠키가 없는 요청(서비스 단위 테스트, 쿠키를 보지 않는 호출부). */
    PostUnlockCheck NONE = post -> false;

    boolean isUnlocked(Post post);
}
