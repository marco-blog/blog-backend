package net.java21.blog.backend.comment.repository;

import java.time.Instant;

import net.java21.blog.backend.comment.domain.CommentStatus;

/** 글 댓글 목록 한 줄(DTO projection). 작성자(프로필 이미지 키 포함)는 같은 쿼리에서 읽는다. */
public record CommentRow(Long id, Long parentId, String content, CommentStatus status, Long userId,
        String nickname, String profileMediaKey, Instant createdAt, Instant updatedAt) {
}
