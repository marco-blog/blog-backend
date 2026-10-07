package net.java21.blog.backend.sidebar.dto;

import java.time.Instant;

/** 사이드바 최근 글·인기 글 한 줄. */
public record SidebarPostResponse(Long id, String title, Instant publishedAt) {
}
