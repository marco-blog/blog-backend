package net.java21.blog.backend.external.thumbnail;

/** 소유 인증(신청 때 인증·넘겨받기)이 커밋되면 그 블로그의 최근 글 썸네일을 소급해 받는다(007 research E7). */
public record ThumbnailBackfillRequested(long externalBlogId) {
}
