package net.java21.blog.backend.blog.repository;

import java.time.Instant;

/** 내 블로그 목록 한 줄(DTO projection). */
public record MyBlogRow(Long id, String handle, String title, String coverMediaKey, long postCount, Instant createdAt) {
}
