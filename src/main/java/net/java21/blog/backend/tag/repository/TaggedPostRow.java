package net.java21.blog.backend.tag.repository;

import java.time.Instant;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostSummaryRow;

/** 서비스 전체 태그별 글 한 줄(DTO projection): 글 목록 한 줄 + 블로그 주소. */
public record TaggedPostRow(Long id, String title, String summary, String thumbnailUrl, Long categoryId,
        String categoryName, int viewCount, int commentCount, PostVisibility visibility, PostStatus status,
        Instant publishedAt, Instant updatedAt, String blogHandle, boolean notice) {

    public PostSummaryRow toSummaryRow() {
        return new PostSummaryRow(id, title, summary, thumbnailUrl, categoryId, categoryName, viewCount,
                commentCount, visibility, status, publishedAt, updatedAt, notice);
    }
}
