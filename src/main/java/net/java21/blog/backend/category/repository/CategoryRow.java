package net.java21.blog.backend.category.repository;

/** 카테고리 한 줄(DTO projection). {@code parentId}가 null이면 상위 카테고리. */
public record CategoryRow(Long id, Long parentId, String name, int sortOrder) {
}
