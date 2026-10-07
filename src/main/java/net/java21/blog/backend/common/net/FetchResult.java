package net.java21.blog.backend.common.net;

import java.net.URI;

/**
 * 외부 요청 결과(007 research E2). 성공이면 {@link #failure()}가 null이다. 304는 성공이며 본문이 없다.
 *
 * @param status       마지막 응답의 HTTP 상태(요청을 보내지 못했으면 0)
 * @param body         본문(HEAD·304·실패면 null). 크기 상한 안
 * @param contentType  {@code Content-Type}
 * @param etag         {@code ETag}
 * @param lastModified {@code Last-Modified} 원문
 * @param finalUri     리다이렉트를 따라간 마지막 주소(요청을 보내지 못했으면 그 주소)
 * @param failure      실패 종류
 * @param blockReason  {@link FetchFailure#BLOCKED_ADDRESS}일 때 이유
 */
public record FetchResult(int status, byte[] body, String contentType, String etag, String lastModified, URI finalUri,
        FetchFailure failure, BlockReason blockReason) {

    public static FetchResult ok(int status, byte[] body, String contentType, String etag, String lastModified,
            URI finalUri) {
        return new FetchResult(status, body, contentType, etag, lastModified, finalUri, null, null);
    }

    public static FetchResult failed(FetchFailure failure, int status, URI uri) {
        return new FetchResult(status, null, null, null, null, uri, failure, null);
    }

    public static FetchResult blocked(BlockReason reason, URI uri) {
        return new FetchResult(0, null, null, null, null, uri, FetchFailure.BLOCKED_ADDRESS, reason);
    }

    public boolean isSuccess() {
        return failure == null;
    }

    public boolean notModified() {
        return failure == null && status == 304;
    }

    /** 실패면 그 상태 코드(보낸 요청이 없으면 null). */
    public Integer httpStatusOrNull() {
        return status == 0 ? null : status;
    }
}
