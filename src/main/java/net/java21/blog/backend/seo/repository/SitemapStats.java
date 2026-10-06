package net.java21.blog.backend.seo.repository;

import java.time.Instant;

/**
 * 본문 노출 가능 글 전체 수(posts 파일 수 계산)와 그중 가장 늦은 수정 시각(색인의 {@code Last-Modified}, 글이 없으면 null).
 */
public record SitemapStats(long postCount, Instant lastModified) {
}
