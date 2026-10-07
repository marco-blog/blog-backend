package net.java21.blog.backend.external.domain;

/** 외부 글을 내린 이유(007 data-model external_posts.removed_reason). */
public enum RemovedReason {
    LINK_BROKEN, BLOG_BLOCKED, MEMBER_WITHDRAWN, REPORT, ADMIN
}
