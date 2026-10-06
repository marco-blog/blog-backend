package net.java21.blog.backend.category.dto;

/**
 * {@code POST /blogs/{handle}/categories}. 이름은 앞뒤 공백을 지운 뒤 1~50자(서비스 검증).
 *
 * @param parentId 상위 카테고리(생략하면 상위 카테고리를 만든다)
 */
public record CreateCategoryRequest(String name, Long parentId) {
}
