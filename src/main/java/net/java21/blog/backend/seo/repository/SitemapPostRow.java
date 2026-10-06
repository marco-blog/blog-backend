package net.java21.blog.backend.seo.repository;

import java.time.Instant;

/** 사이트맵의 글 주소 한 줄: {@code /{blogHandle}/{id}}, {@code lastmod}는 글 수정 시각. */
public record SitemapPostRow(Long id, String blogHandle, Instant updatedAt) {
}
