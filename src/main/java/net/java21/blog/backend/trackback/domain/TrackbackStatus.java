package net.java21.blog.backend.trackback.domain;

/**
 * 받은 트랙백 상태(trackbacks.status, 005 data-model). DELETED는 글 주인이 지운 것(행을 남겨 같은 주소 재수신을 막는다),
 * HIDDEN은 관리자가 숨긴 것이다.
 */
public enum TrackbackStatus {
    ACTIVE,
    DELETED,
    HIDDEN
}
