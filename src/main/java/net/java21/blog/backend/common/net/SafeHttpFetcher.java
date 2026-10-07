package net.java21.blog.backend.common.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 외부로 나가는 모든 요청(피드, 블로그 HTML, 대표 이미지, 원문 링크 점검)의 유일한 길(007 research E2, FR-116).
 * <ul>
 * <li>JDK {@link HttpClient}(HTTP/1.1, 리다이렉트 직접 처리, 프록시 없음). 요청 전과 리다이렉트마다 {@link OutboundUrlGuard}
 * (스킴·포트·사용자 정보·해석한 모든 주소가 공인)와 우리 서비스 호스트 검사({@code SELF}).</li>
 * <li>본문은 읽으면서 용도별 상한({@link Limit})을 넘는 순간 끊는다. {@code Content-Length}가 이미 크면 읽지 않는다.</li>
 * <li>{@code Accept-Encoding}은 보내지 않는다(identity). 요청 하나 전체 시간은 {@code request-timeout}.</li>
 * </ul>
 * 한계: JDK 클라이언트는 검사한 IP로 연결을 고정하지 못해 DNS 재바인딩을 완전히 막지 못한다(운영 문서에 방화벽 권고).
 */
public class SafeHttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(SafeHttpFetcher.class);
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    public static final String FEED_ACCEPT =
            "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5";
    public static final String PAGE_ACCEPT = "text/html, application/xhtml+xml, application/xml;q=0.9, */*;q=0.5";
    public static final String IMAGE_ACCEPT = "image/jpeg, image/png, image/gif, image/webp;q=0.9";

    /** 용도별 본문 크기 상한. {@code NONE}은 본문을 읽지 않는다(링크 점검). */
    public enum Limit {
        FEED, PAGE, IMAGE, NONE
    }

    /**
     * 요청 하나.
     *
     * @param uri          주소
     * @param method       {@code GET} 또는 {@code HEAD}
     * @param limit        본문 상한
     * @param accept       {@code Accept}(null이면 보내지 않음)
     * @param etag         {@code If-None-Match}
     * @param lastModified {@code If-Modified-Since}
     * @param range        {@code Range}(링크 점검의 0바이트 GET)
     */
    public record Request(URI uri, String method, Limit limit, String accept, String etag, String lastModified,
            String range) {

        public static Request get(URI uri, Limit limit) {
            return new Request(uri, "GET", limit, switch (limit) {
                case FEED -> FEED_ACCEPT;
                case PAGE -> PAGE_ACCEPT;
                case IMAGE -> IMAGE_ACCEPT;
                case NONE -> null;
            }, null, null, null);
        }

        public static Request head(URI uri) {
            return new Request(uri, "HEAD", Limit.NONE, null, null, null, null);
        }

        public Request conditional(String ifNoneMatch, String ifModifiedSince) {
            return new Request(uri, method, limit, accept, ifNoneMatch, ifModifiedSince, range);
        }

        public Request withRange(String value) {
            return new Request(uri, method, limit, accept, etag, lastModified, value);
        }
    }

    /**
     * 설정값.
     *
     * @param connectTimeout 연결 시간
     * @param requestTimeout 요청 하나(리다이렉트 한 번) 전체 시간
     * @param maxRedirects   리다이렉트 상한
     * @param maxFeedBytes   피드 상한
     * @param maxPageBytes   HTML 상한
     * @param maxImageBytes  이미지 상한
     * @param selfHosts      우리 서비스 호스트(하위 도메인 포함 거부)
     * @param userAgent      {@code User-Agent}
     */
    public record Settings(Duration connectTimeout, Duration requestTimeout, int maxRedirects, long maxFeedBytes,
            long maxPageBytes, long maxImageBytes, Set<String> selfHosts, String userAgent) {

        public Settings {
            selfHosts = selfHosts.stream().map(h -> h.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors
                    .toUnmodifiableSet());
        }

        long limit(Limit limit) {
            return switch (limit) {
                case FEED -> maxFeedBytes;
                case PAGE -> maxPageBytes;
                case IMAGE -> maxImageBytes;
                case NONE -> 0;
            };
        }
    }

    private final OutboundUrlGuard guard;
    private final Settings settings;
    private final HttpClient client;

    public SafeHttpFetcher(OutboundUrlGuard guard, Settings settings) {
        this.guard = guard;
        this.settings = settings;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(settings.connectTimeout())
                // 검사한 주소로 직접 연결한다(프록시를 거치면 내부망 검사가 의미 없음).
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    public Settings settings() {
        return settings;
    }

    /** {@code GET}(용도별 {@code Accept}). */
    public FetchResult get(URI uri, Limit limit) {
        return fetch(Request.get(uri, limit));
    }

    /**
     * 주소만 검사한다(요청 없음). 통과하면 null, 아니면 거부 이유. 회원이 넣은 주소를 미리 검사할 때 쓴다.
     */
    public BlockReason check(URI uri) {
        try {
            guardCheck(uri);
            return null;
        } catch (Blocked e) {
            return e.reason;
        } catch (OutboundBlockedException e) {
            return e.reason() == OutboundBlockedException.Reason.UNRESOLVABLE ? null : map(e);
        }
    }

    /** 우리 서비스 호스트인지(하위 도메인 포함). */
    public boolean isSelf(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        for (String self : settings.selfHosts()) {
            if (h.equals(self) || h.endsWith("." + self)) {
                return true;
            }
        }
        return false;
    }

    public FetchResult fetch(Request request) {
        URI uri = request.uri();
        int redirects = 0;
        while (true) {
            try {
                guardCheck(uri);
            } catch (Blocked e) {
                return FetchResult.blocked(e.reason, uri);
            } catch (OutboundBlockedException e) {
                if (e.reason() == OutboundBlockedException.Reason.UNRESOLVABLE) {
                    return FetchResult.failed(FetchFailure.DNS_ERROR, 0, uri);
                }
                return FetchResult.blocked(map(e), uri);
            }
            Attempt attempt = send(uri, request);
            if (attempt.result != null) {
                return attempt.result;
            }
            HttpResponse<byte[]> response = attempt.response;
            int status = response.statusCode();
            if (REDIRECTS.contains(status)) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    return FetchResult.failed(FetchFailure.HTTP_ERROR, status, uri);
                }
                if (redirects >= settings.maxRedirects()) {
                    log.debug("Too many redirects: {}", uri.getHost());
                    return FetchResult.failed(FetchFailure.HTTP_ERROR, status, uri);
                }
                URI next;
                try {
                    next = uri.resolve(new URI(location.strip().replace(" ", "%20")));
                } catch (URISyntaxException | IllegalArgumentException e) {
                    return FetchResult.blocked(BlockReason.INVALID_URL, uri);
                }
                redirects++;
                uri = next;
                continue;
            }
            if (status == 304 || (status >= 200 && status < 300)) {
                return FetchResult.ok(status, status == 304 ? null : response.body(),
                        response.headers().firstValue("Content-Type").orElse(null),
                        response.headers().firstValue("ETag").orElse(null),
                        response.headers().firstValue("Last-Modified").orElse(null), uri);
            }
            return FetchResult.failed(FetchFailure.HTTP_ERROR, status, uri);
        }
    }

    private record Attempt(HttpResponse<byte[]> response, FetchResult result) {
    }

    private Attempt send(URI uri, Request request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(settings.requestTimeout())
                .header("User-Agent", settings.userAgent());
        if ("HEAD".equals(request.method())) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }
        if (request.accept() != null) {
            builder.header("Accept", request.accept());
        }
        if (request.etag() != null && !request.etag().isBlank()) {
            builder.header("If-None-Match", request.etag());
        }
        if (request.lastModified() != null && !request.lastModified().isBlank()) {
            builder.header("If-Modified-Since", request.lastModified());
        }
        if (request.range() != null) {
            builder.header("Range", request.range());
        }
        long limit = settings.limit(request.limit());
        CompletableFuture<HttpResponse<byte[]>> future =
                client.sendAsync(builder.build(), info -> new LimitedSubscriber(info, limit));
        try {
            // 연결·머리글·본문 전체를 한 시간 안에(머리글만 재는 HttpRequest.timeout으로는 느린 본문을 막지 못함).
            long wait = settings.requestTimeout().toMillis() + 50;
            return new Attempt(future.get(wait, TimeUnit.MILLISECONDS), null);
        } catch (TimeoutException e) {
            future.cancel(true);
            return new Attempt(null, FetchResult.failed(FetchFailure.TIMEOUT, 0, uri));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new Attempt(null, FetchResult.failed(FetchFailure.TIMEOUT, 0, uri));
        } catch (ExecutionException | CancellationException e) {
            return new Attempt(null, classify(e.getCause() == null ? e : e.getCause(), uri));
        }
    }

    private static FetchResult classify(Throwable cause, URI uri) {
        Throwable t = cause;
        while (t != null) {
            if (t instanceof TooLarge large) {
                return FetchResult.failed(FetchFailure.TOO_LARGE, large.status, uri);
            }
            if (t instanceof HttpConnectTimeoutException || t instanceof HttpTimeoutException) {
                return FetchResult.failed(FetchFailure.TIMEOUT, 0, uri);
            }
            if (t instanceof UnknownHostException) {
                return FetchResult.failed(FetchFailure.DNS_ERROR, 0, uri);
            }
            if (t instanceof ConnectException) {
                return FetchResult.failed(FetchFailure.HTTP_ERROR, 0, uri);
            }
            t = t.getCause();
        }
        log.debug("External request failed: {}", cause.toString());
        return FetchResult.failed(FetchFailure.HTTP_ERROR, 0, uri);
    }

    private void guardCheck(URI uri) {
        if (uri == null || uri.getScheme() == null) {
            throw new Blocked(BlockReason.INVALID_URL);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new Blocked(BlockReason.SCHEME);
        }
        if (uri.getRawUserInfo() != null) {
            throw new Blocked(BlockReason.CREDENTIALS);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new Blocked(BlockReason.INVALID_URL);
        }
        if (isSelf(uri.getHost())) {
            throw new Blocked(BlockReason.SELF);
        }
        guard.check(uri);
    }

    private static BlockReason map(OutboundBlockedException e) {
        return switch (e.reason()) {
            case PORT_NOT_ALLOWED -> BlockReason.PORT;
            case BLOCKED_ADDRESS -> BlockReason.PRIVATE_ADDRESS;
            case INVALID_URL, UNRESOLVABLE -> BlockReason.INVALID_URL;
        };
    }

    private static final class Blocked extends RuntimeException {
        private final BlockReason reason;

        Blocked(BlockReason reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }
    }

    /** 크기 상한을 넘은 본문. */
    static final class TooLarge extends IOException {
        private final int status;

        TooLarge(int status) {
            super("Response too large");
            this.status = status;
        }
    }

    /** 상한까지만 모으고 넘으면 구독을 끊는 본문 수신기. 상한 0이면 본문을 읽지 않는다. */
    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final long limit;
        private final int status;
        private final boolean skip;
        private Flow.Subscription subscription;

        LimitedSubscriber(HttpResponse.ResponseInfo info, long limit) {
            this.limit = limit;
            this.status = info.statusCode();
            long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1);
            boolean success = status >= 200 && status < 300;
            // 성공 응답이 아니거나(리다이렉트·오류) 본문이 필요 없으면 읽지 않는다.
            this.skip = limit <= 0 || !success;
            if (!skip && declared > limit) {
                result.completeExceptionally(new TooLarge(status));
            }
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            if (result.isDone()) {
                s.cancel();
                return;
            }
            if (skip) {
                s.cancel();
                result.complete(null);
                return;
            }
            s.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int n = item.remaining();
                if (buffer.size() + (long) n > limit) {
                    subscription.cancel();
                    result.completeExceptionally(new TooLarge(status));
                    return;
                }
                byte[] chunk = new byte[n];
                item.get(chunk);
                buffer.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(skip ? null : buffer.toByteArray());
        }
    }
}
