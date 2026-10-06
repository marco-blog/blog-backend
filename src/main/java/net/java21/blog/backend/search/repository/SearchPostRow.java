package net.java21.blog.backend.search.repository;

import java.time.Instant;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostExposure;

/** 검색 결과 한 줄(DTO projection): 글 요약 + 블로그 handle·title. 태그는 따로 일괄 조회한다. */
public record SearchPostRow(Long id, String title, String summary, String thumbnailUrl, Long categoryId,
        String categoryName, int viewCount, int commentCount, PostVisibility visibility, PostStatus status,
        Instant publishedAt, Instant updatedAt, String blogHandle, String blogTitle) {

    /** 본문 노출 가능 글인지. 아니면(004 보호 글) 제목으로만 검색되고 결과에도 제목만 준다. */
    public boolean bodyVisible() {
        return PostExposure.isBodyVisibleListed(visibility);
    }
}
