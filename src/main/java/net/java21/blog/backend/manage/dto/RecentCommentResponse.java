package net.java21.blog.backend.manage.dto;

import java.time.Instant;

/**
 * 대시보드 최근 댓글 한 줄(contracts/api.md {@code Comment + { postId, postTitle }}). 댓글은 US3에서 만들며, 그 전에는 빈 목록이다.
 */
public record RecentCommentResponse(Long id, String content, Author author, boolean deleted, Instant createdAt,
        Instant updatedAt, Long postId, String postTitle) {

    public record Author(Long userId, String nickname, String profileImageUrl) {
    }
}
