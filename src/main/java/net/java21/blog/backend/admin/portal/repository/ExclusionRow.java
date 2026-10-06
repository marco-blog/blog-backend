package net.java21.blog.backend.admin.portal.repository;

import java.time.Instant;

/** 관리자 포털 제외 목록 한 줄(DTO projection): 제외 + 글 제목·블로그 주소 + 처리한 관리자. */
public record ExclusionRow(Long postId, String postTitle, String blogHandle, String reason, Long excludedById,
        String excludedByNickname, Instant createdAt, Instant updatedAt) {
}
