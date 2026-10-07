package net.java21.blog.backend.sidebar.dto;

import java.time.Instant;

/** 사이드바 최근 댓글(비밀이 아닌 댓글만): 내용 앞 50자, 회원 닉네임 또는 비회원 이름. */
public record SidebarCommentResponse(Long id, Long postId, String postTitle, String excerpt, String authorName,
        boolean guest, Instant createdAt) {
}
