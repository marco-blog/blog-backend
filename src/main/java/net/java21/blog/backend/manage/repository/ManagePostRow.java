package net.java21.blog.backend.manage.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostSummaryResponse;

/**
 * 블로그 관리 글 목록 한 줄(DTO projection). 공개 목록과 달리 작성 중 사본 여부({@code hasDraft})와 휴지통 시각을 함께 읽는다.
 * 카테고리·태그는 US2에서 채운다.
 */
public record ManagePostRow(Long id, String title, String summary, String thumbnailUrl, int viewCount,
        int commentCount, PostVisibility visibility, PostStatus status, Instant publishedAt, Instant updatedAt,
        boolean hasDraft, Instant deletedAt) {

    /** 주인용 응답. 휴지통 글이면 {@code purgeAt} = {@code deletedAt} + 보관 기간(FR-084). */
    public PostSummaryResponse toResponse(Duration trashRetention) {
        Instant purgeAt = deletedAt == null ? null : deletedAt.plus(trashRetention);
        return new PostSummaryResponse(id, title, summary, thumbnailUrl, null, List.of(), viewCount, commentCount,
                visibility, status, publishedAt, updatedAt, hasDraft, deletedAt, purgeAt);
    }
}
