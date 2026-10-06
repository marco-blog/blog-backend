package net.java21.blog.backend.subscription.repository;

import java.time.Instant;

import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;

/** 구독 피드 한 줄(DTO projection): 글 요약 + 블로그 handle·title + 작성자 닉네임·프로필 이미지 키. 태그는 따로 일괄 조회한다. */
public record FeedPostRow(Long id, String title, String summary, String thumbnailUrl, Long categoryId,
        String categoryName, int viewCount, int commentCount, PostVisibility visibility, PostStatus status,
        Instant publishedAt, Instant updatedAt, String blogHandle, String blogTitle, String authorNickname,
        String authorProfileMediaKey) {
}
