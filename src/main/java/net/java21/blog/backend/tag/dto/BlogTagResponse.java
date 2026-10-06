package net.java21.blog.backend.tag.dto;

/** 블로그 태그 목록 한 줄 {@code { name, postCount }}(GET /blogs/{handle}/tags). {@code postCount}는 목록 노출 가능 글 수. */
public record BlogTagResponse(String name, long postCount) {
}
