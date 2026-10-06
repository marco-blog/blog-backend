package net.java21.blog.backend.category.dto;

/** {@code PATCH /blogs/{handle}/categories/{id}} {@code { name? }}. 생략하면 바꾸지 않는다. */
public record UpdateCategoryRequest(String name) {
}
