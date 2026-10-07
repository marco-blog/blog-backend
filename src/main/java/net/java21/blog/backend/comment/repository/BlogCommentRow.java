package net.java21.blog.backend.comment.repository;

import java.time.Instant;

/**
 * 블로그 관리 댓글 목록·대시보드 최근 댓글 한 줄(DTO projection). 글 제목·작성자(프로필 이미지 키 포함)를 같은 쿼리에서 읽는다.
 * 004: 비밀 여부(답글이면 부모의 비밀 여부 포함)와 비회원 이름.
 */
public record BlogCommentRow(Long id, String content, Long userId, String nickname, String profileMediaKey,
        Instant createdAt, Instant updatedAt, Long postId, String postTitle, boolean secret, Boolean parentSecret,
        String guestName) {

    public BlogCommentRow(Long id, String content, Long userId, String nickname, String profileMediaKey,
            Instant createdAt, Instant updatedAt, Long postId, String postTitle) {
        this(id, content, userId, nickname, profileMediaKey, createdAt, updatedAt, postId, postTitle, false, null,
                null);
    }
}
