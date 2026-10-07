package net.java21.blog.backend.comment.repository;

import java.time.Instant;

import net.java21.blog.backend.comment.domain.CommentStatus;

/**
 * 글 댓글 목록 한 줄(DTO projection). 작성자(프로필 이미지 키 포함)는 같은 쿼리의 LEFT JOIN으로 읽는다.
 * 004: 비밀 여부와 비회원 이름({@code userId} null이면 비회원).
 */
public record CommentRow(Long id, Long parentId, String content, CommentStatus status, Long userId,
        String nickname, String profileMediaKey, Instant createdAt, Instant updatedAt, boolean secret,
        String guestName) {

    public CommentRow(Long id, Long parentId, String content, CommentStatus status, Long userId, String nickname,
            String profileMediaKey, Instant createdAt, Instant updatedAt) {
        this(id, parentId, content, status, userId, nickname, profileMediaKey, createdAt, updatedAt, false, null);
    }
}
