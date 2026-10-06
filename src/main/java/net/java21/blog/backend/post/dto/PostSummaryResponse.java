package net.java21.blog.backend.post.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;

/**
 * 글 목록 한 줄(contracts/api.md {@code PostSummary}). {@code deletedAt}·{@code purgeAt}은 휴지통 글에만 있다.
 * 카테고리는 미분류면 null, 태그는 이름순.
 */
public record PostSummaryResponse(
        Long id,
        String title,
        String summary,
        String thumbnailUrl,
        CategoryRef category,
        List<String> tags,
        int viewCount,
        int commentCount,
        PostVisibility visibility,
        PostStatus status,
        Instant publishedAt,
        Instant updatedAt,
        boolean hasDraft,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant deletedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant purgeAt) {
}
