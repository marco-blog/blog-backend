package net.java21.blog.backend.post.domain;

/** 글 상태(posts.status). 004에서 SCHEDULED, 005에서 HIDDEN을 더한다. */
public enum PostStatus {
    DRAFT,
    PUBLISHED,
    DELETED
}
