package net.java21.blog.backend.post.dto;

/** 이전·다음 글 {@code { id, title }}. */
public record PostLink(Long id, String title) {
}
