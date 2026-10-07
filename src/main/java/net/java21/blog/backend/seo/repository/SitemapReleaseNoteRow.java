package net.java21.blog.backend.seo.repository;

import java.time.Instant;

/** 사이트맵의 릴리스 노트 주소 {@code /updates/v{version}}(003 FR-164). */
public record SitemapReleaseNoteRow(String version, Instant updatedAt) {
}
