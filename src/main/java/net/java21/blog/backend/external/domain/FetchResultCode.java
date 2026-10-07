package net.java21.blog.backend.external.domain;

import net.java21.blog.backend.common.net.FetchFailure;

/** 마지막 수집 결과(007 data-model external_blogs.last_fetch_result). */
public enum FetchResultCode {
    OK, NOT_MODIFIED, HTTP_ERROR, TIMEOUT, TOO_LARGE, PARSE_ERROR, BLOCKED_ADDRESS, DNS_ERROR;

    public static FetchResultCode of(FetchFailure failure) {
        return switch (failure) {
            case HTTP_ERROR -> HTTP_ERROR;
            case TIMEOUT -> TIMEOUT;
            case TOO_LARGE -> TOO_LARGE;
            case BLOCKED_ADDRESS -> BLOCKED_ADDRESS;
            case DNS_ERROR -> DNS_ERROR;
        };
    }
}
