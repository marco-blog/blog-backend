package net.java21.blog.backend.syndication.repository;

import java.time.Instant;

import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostExposure;

/** 피드 항목 한 줄(DTO projection). 카테고리는 미분류면 null, 태그는 따로 일괄 조회한다. */
public record FeedItemRow(Long id, String title, String contentHtml, String summary, PostVisibility visibility,
        Instant publishedAt, Instant updatedAt, String categoryName) {

    /** 본문 노출 가능 글인지. 아니면(004 보호 글) 제목·링크·시각만 담는다(FR-047). */
    public boolean bodyVisible() {
        return PostExposure.isBodyVisibleListed(visibility);
    }
}
