package net.java21.blog.backend.post.domain;

/**
 * 글 상태(posts.status). SCHEDULED(004 FR-064)는 예약 시각이 되면 정기 작업이 PUBLISHED로 바꾼다. 005가 HIDDEN을 더한다.
 */
public enum PostStatus {
    DRAFT,
    PUBLISHED,
    DELETED,
    SCHEDULED
}
