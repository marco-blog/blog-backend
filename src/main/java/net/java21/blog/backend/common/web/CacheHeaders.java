package net.java21.blog.backend.common.web;

/** 컨트롤러가 직접 정하는 {@code Cache-Control} 값(api-guidelines 8절). 정하지 않은 응답에는 Spring Security 기본값(no-store)이 붙는다. */
public final class CacheHeaders {

    /**
     * 공개 GET이지만 보는 사람마다 내용이 다른 응답(002: {@code likedByMe}·{@code subscribedByMe}). 공유 캐시에 두지 않고 매번 다시 확인한다.
     */
    public static final String PRIVATE_NO_CACHE = "private, no-cache";

    private CacheHeaders() {
    }
}
