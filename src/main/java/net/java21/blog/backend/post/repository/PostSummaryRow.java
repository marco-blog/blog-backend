package net.java21.blog.backend.post.repository;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostSummaryResponse;

/** 글 목록 한 줄(DTO projection). 응답으로 바꿀 때 카테고리·태그(US2)를 채운다. */
public record PostSummaryRow(Long id, String title, String summary, String thumbnailUrl, int viewCount,
        int commentCount, PostVisibility visibility, PostStatus status, Instant publishedAt, Instant updatedAt) {

    /** 공개 목록용 응답. 작성 중 사본 여부는 주인 관리 화면의 정보라 공개 목록에서는 false. */
    public PostSummaryResponse toPublicResponse() {
        return new PostSummaryResponse(id, title, summary, thumbnailUrl, null, List.of(), viewCount, commentCount,
                visibility, status, publishedAt, updatedAt, false, null, null);
    }
}
