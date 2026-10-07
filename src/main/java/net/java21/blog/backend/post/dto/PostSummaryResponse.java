package net.java21.blog.backend.post.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostExposure;

/**
 * 글 목록 한 줄(contracts/api.md {@code PostSummary}). {@code deletedAt}·{@code purgeAt}은 휴지통 글에만 있다.
 * 카테고리는 미분류면 null, 태그는 이름순. {@code notice}는 공지 글(004 FR-059). {@code scheduledAt}은 관리 목록의 예약 글에만 값이다.
 * 주인 외 목록에서는 {@link #forReader}로 보호 글의 요약·대표 이미지를 가린다(004 research B1).
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
        boolean notice,
        Instant scheduledAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant deletedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant purgeAt) {

    /**
     * 주인 외 목록용(블로그 홈·카테고리·태그·공지·보관함·서비스 태그): 본문 노출 가능이 아닌 글(보호 글)은 제목만 남기고
     * {@code summary}·{@code thumbnailUrl}을 null로 바꾼다(001 contracts {@code PostSummary} 규칙). 예약 시각은 싣지 않는다.
     */
    public PostSummaryResponse forReader() {
        boolean bodyVisible = PostExposure.isBodyVisibleListed(visibility);
        return new PostSummaryResponse(id, title, bodyVisible ? summary : null, bodyVisible ? thumbnailUrl : null,
                category, tags, viewCount, commentCount, visibility, status, publishedAt, updatedAt, hasDraft, notice,
                null, deletedAt, purgeAt);
    }
}
