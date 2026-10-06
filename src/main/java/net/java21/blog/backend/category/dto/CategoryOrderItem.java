package net.java21.blog.backend.category.dto;

/**
 * {@code PUT /blogs/{handle}/categories/order}의 한 항목 {@code { id, parentId, sortOrder }}. {@code parentId}가 null이면 상위 카테고리.
 * 요청에 없는 카테고리는 지금 위치를 유지한다.
 */
public record CategoryOrderItem(Long id, Long parentId, Integer sortOrder) {
}
