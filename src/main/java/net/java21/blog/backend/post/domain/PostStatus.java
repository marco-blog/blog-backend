package net.java21.blog.backend.post.domain;

/**
 * 글 상태(posts.status). SCHEDULED(004 FR-064)는 예약 시각이 되면 정기 작업이 PUBLISHED로 바꾼다.
 * HIDDEN(005 FR-041)은 관리자가 숨긴 글이다. 숨김 직전 상태는 {@code status_before_hidden}에 두고 해제하면 되돌린다.
 * PUBLISHED가 아니므로 노출 조각({@code PostExposure})이 모든 공개 목록·상세(주인 외)에서 뺀다.
 */
public enum PostStatus {
    DRAFT,
    PUBLISHED,
    DELETED,
    SCHEDULED,
    HIDDEN
}
