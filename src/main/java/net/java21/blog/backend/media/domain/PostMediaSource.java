package net.java21.blog.backend.media.domain;

/** 글이 이미지를 참조하는 곳({@code post_media.source}). */
public enum PostMediaSource {
    /** 발행본 본문(대표 이미지는 본문 이미지 중 하나다). */
    PUBLISHED,
    /** 작성 중 사본. */
    DRAFT
}
