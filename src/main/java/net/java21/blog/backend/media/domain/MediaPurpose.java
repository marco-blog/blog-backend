package net.java21.blog.backend.media.domain;

/** 이미지 용도({@code media.owner_type}, 업로드 요청의 {@code purpose}). */
public enum MediaPurpose {
    /** 에디터 업로드(기본). */
    POST,
    /** 프로필 이미지. */
    PROFILE,
    /** 블로그 대표 이미지. */
    BLOG_COVER
}
