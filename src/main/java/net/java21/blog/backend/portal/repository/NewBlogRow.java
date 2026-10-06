package net.java21.blog.backend.portal.repository;

import java.time.Instant;

/** 새로 시작한 블로그 한 줄(003 FR-087, research P6). */
public record NewBlogRow(String handle, String title, String description, String coverMediaKey, String ownerNickname,
        String ownerProfileMediaKey, Instant firstPublishedAt) {
}
