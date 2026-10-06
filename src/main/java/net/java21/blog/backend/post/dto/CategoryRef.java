package net.java21.blog.backend.post.dto;

/** 글의 카테고리 {@code { id, name }}. 미분류는 null. */
public record CategoryRef(Long id, String name) {
}
