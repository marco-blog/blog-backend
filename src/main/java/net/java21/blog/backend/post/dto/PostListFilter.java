package net.java21.blog.backend.post.dto;

/**
 * 블로그 글 목록 조건({@code GET /blogs/{handle}/posts?category=&tag=}, FR-026). 둘 다 생략할 수 있다.
 *
 * @param categoryId 카테고리(상위면 하위 카테고리 글 포함)
 * @param tag        정규화한 태그 이름
 */
public record PostListFilter(Long categoryId, String tag) {

    public static final PostListFilter NONE = new PostListFilter(null, null);
}
