package net.java21.blog.backend.post.domain;

/**
 * 글 공개 범위(posts.visibility). PROTECTED(004 FR-062)는 목록에 제목만 나오고 비밀번호를 맞힌 사람에게만 본문을 보인다
 * ({@code password_hash}가 함께 있어야 한다, {@code ck_posts_protected_password}).
 */
public enum PostVisibility {
    PUBLIC,
    PRIVATE,
    PROTECTED
}
