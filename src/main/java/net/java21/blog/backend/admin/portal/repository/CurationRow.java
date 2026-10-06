package net.java21.blog.backend.admin.portal.repository;

import java.time.Instant;

/** 관리자 추천 목록 한 줄(DTO projection): 추천 + 글 제목·블로그 주소 + 지정한 관리자. */
public record CurationRow(Long id, Long postId, String postTitle, String blogHandle, Instant startsAt,
        Instant endsAt, int sortOrder, Long createdById, String createdByNickname, Instant createdAt,
        Instant updatedAt) {
}
