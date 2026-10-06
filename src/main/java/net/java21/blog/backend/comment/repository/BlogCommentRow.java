package net.java21.blog.backend.comment.repository;

import java.time.Instant;

/** 블로그 관리 댓글 목록·대시보드 최근 댓글 한 줄(DTO projection). 글 제목·작성자를 같은 쿼리에서 읽는다. */
public record BlogCommentRow(Long id, String content, Long userId, String nickname, Instant createdAt,
        Instant updatedAt, Long postId, String postTitle) {
}
