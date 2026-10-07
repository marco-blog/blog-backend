package net.java21.blog.backend.trackback.dto;

import java.time.Instant;

import net.java21.blog.backend.trackback.repository.TrackbackRow;

/** 글 상세의 트랙백 한 줄(005 contracts/api.md {@code Trackback}). {@code internal}: 서비스 안 글이 보낸 것. 모든 값은 텍스트로만 출력한다. */
public record TrackbackResponse(Long id, String title, String excerpt, String blogName, String url,
        Instant receivedAt, boolean internal) {

    public static TrackbackResponse of(TrackbackRow row) {
        return new TrackbackResponse(row.id(), row.title(), row.excerpt(), row.blogName(), row.url(),
                row.receivedAt(), row.sourcePostId() != null);
    }
}
