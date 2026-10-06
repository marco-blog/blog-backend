package net.java21.blog.backend.blog.domain;

/** 블로그 피드(RSS·Atom)의 공개 형태(FR-046, {@code blogs.feed_content_mode}). */
public enum FeedContentMode {
    /** 본문 전체(HTML). */
    FULL,
    /** 요약만. */
    SUMMARY
}
