package net.java21.blog.backend.post.dto;

import java.time.Instant;

/** 임시저장 결과 {@code { id, savedAt }}. */
public record SavedDraftResponse(Long id, Instant savedAt) {
}
