package net.java21.blog.backend.post.repository;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.CategoryRef;
import net.java21.blog.backend.post.dto.PostSummaryResponse;

/**
 * 글 목록 한 줄(DTO projection, 카테고리는 LEFT JOIN으로 함께 읽음). 태그는 목록의 글 id로 한 번에 읽어
 * ({@code TagQueryRepository#findTagNames}) 응답으로 바꿀 때 넣는다.
 */
public record PostSummaryRow(Long id, String title, String summary, String thumbnailUrl, Long categoryId,
        String categoryName, int viewCount, int commentCount, PostVisibility visibility, PostStatus status,
        Instant publishedAt, Instant updatedAt) {

    /** 공개 목록용 응답. 작성 중 사본 여부는 주인 관리 화면의 정보라 공개 목록에서는 false. */
    public PostSummaryResponse toPublicResponse(List<String> tags) {
        return new PostSummaryResponse(id, title, summary, thumbnailUrl, CategoryRef.of(categoryId, categoryName),
                tags == null ? List.of() : tags, viewCount, commentCount, visibility, status, publishedAt, updatedAt,
                false, null, null);
    }
}
