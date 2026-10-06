package net.java21.blog.backend.portal.repository;

import java.time.Instant;

/**
 * 포털 카드 한 줄(DTO projection, 003 FR-085, research P7): 글 요약 + 블로그 + 작성자 닉네임·프로필 이미지 키 + 좋아요·댓글 수.
 */
public record PortalCardRow(Long id, String title, String summary, String thumbnailUrl, Long topicId, Long blogId,
        String blogHandle, String blogTitle, String authorNickname, String authorProfileMediaKey, Instant publishedAt,
        int likeCount, int commentCount) {
}
