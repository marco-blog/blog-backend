package net.java21.blog.backend.seo.repository;

import java.time.Instant;

/** 사이트맵의 블로그 홈 한 줄: {@code lastmod}는 그 블로그의 본문 노출 가능 글 중 가장 최근 발행 시각. */
public record SitemapBlogRow(String handle, Instant lastPublishedAt) {
}
