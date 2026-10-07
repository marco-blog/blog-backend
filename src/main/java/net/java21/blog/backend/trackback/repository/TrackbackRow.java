package net.java21.blog.backend.trackback.repository;

import java.time.Instant;

import net.java21.blog.backend.trackback.domain.TrackbackStatus;

/** 트랙백 목록 한 줄(005 contracts/api.md {@code Trackback}·{@code ManagedTrackback}). {@code sourcePostId}가 있으면 서비스 안 글이 보냈다. */
public record TrackbackRow(Long id, String title, String excerpt, String blogName, String url, Instant receivedAt,
        Long sourcePostId, TrackbackStatus status, Long postId, String postTitle) {
}
