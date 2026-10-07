package net.java21.blog.backend.trackback.dto;

import java.time.Instant;

import net.java21.blog.backend.trackback.domain.TrackbackStatus;
import net.java21.blog.backend.trackback.repository.TrackbackRow;

/**
 * 블로그 관리의 받은 트랙백(005 contracts/api.md {@code ManagedTrackback}). {@code hidden}: 관리자가 숨김(주인이 지울 수 없음),
 * {@code post}: 받은 글.
 */
public record ManagedTrackbackResponse(Long id, String title, String excerpt, String blogName, String url,
        Instant receivedAt, boolean internal, boolean hidden, PostRef post) {

    public record PostRef(Long id, String title) {
    }

    public static ManagedTrackbackResponse of(TrackbackRow row) {
        return new ManagedTrackbackResponse(row.id(), row.title(), row.excerpt(), row.blogName(), row.url(),
                row.receivedAt(), row.sourcePostId() != null, row.status() == TrackbackStatus.HIDDEN,
                new PostRef(row.postId(), row.postTitle()));
    }
}
