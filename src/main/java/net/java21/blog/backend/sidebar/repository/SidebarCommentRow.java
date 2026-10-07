package net.java21.blog.backend.sidebar.repository;

import java.time.Instant;

/** 사이드바 최근 댓글 행(DTO projection). 회원이면 {@code nickname}, 비회원이면 {@code guestName}. */
public record SidebarCommentRow(Long id, Long postId, String postTitle, String content, String nickname,
        String guestName, Instant createdAt) {
}
