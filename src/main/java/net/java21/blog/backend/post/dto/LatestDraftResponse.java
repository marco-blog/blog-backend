package net.java21.blog.backend.post.dto;

import java.time.Instant;

/** 블로그의 가장 최근 임시저장(이어 쓰기 확인용). */
public record LatestDraftResponse(Long id, String title, Instant savedAt) {
}
