package net.java21.blog.backend.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.config.ExternalFetchConfig;
import net.java21.blog.backend.support.StubHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 007 T007: 외부 요청 도구(research E2). 스텁 서버는 127.0.0.1에 뜨고, 가짜 {@link HostResolver}가 {@code 127.0.0.1}을 공인 주소로
 * 답해 내부망 검사({@code allow-private=false})를 통과시킨다. 다른 이름은 가짜 해석기가 정한 주소로 검사만 받는다(연결은 일어나지 않음).
 */
class SafeHttpFetcherTest {

    private StubHttpServer server;
    private SafeHttpFetcher fetcher;

    private final HostResolver resolver = host -> switch (host) {
        case "127.0.0.1" -> List.of(InetAddress.getByName("93.184.216.34"));
        case "internal.test" -> List.of(InetAddress.getByName("10.0.0.5"));
        case "public.test" -> List.of(InetAddress.getByName("93.184.216.35"));
        default -> throw new UnknownHostException(host);
    };

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
        fetcher = fetcher(Duration.ofSeconds(10), 1024);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private SafeHttpFetcher fetcher(Duration requestTimeout, long maxFeed) {
        OutboundProperties outbound = new OutboundProperties(List.of(80, 443, server.port()), false);
        SafeHttpFetcher.Settings settings = new SafeHttpFetcher.Settings(Duration.ofSeconds(5), requestTimeout, 3,
                maxFeed, 512, 4096, Set.of("blog.java21.net", "localhost.example"),
                "java21-blog-feed/1.0 (+https://blog.java21.net/updates)");
        return new SafeHttpFetcher(new OutboundUrlGuard(outbound, resolver), settings);
    }

    @Test
    void okBodyAndValidators() {
        server.respondBytes("/feed.xml", 200, "application/rss+xml; charset=UTF-8",
                "<rss/>".getBytes(StandardCharsets.UTF_8), Map.of("ETag", "\"v1\"",
                        "Last-Modified", "Mon, 05 Oct 2026 00:00:00 GMT"));

        FetchResult result = fetcher.get(server.uri("/feed.xml"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.status()).isEqualTo(200);
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("<rss/>");
        assertThat(result.etag()).isEqualTo("\"v1\"");
        assertThat(result.lastModified()).isEqualTo("Mon, 05 Oct 2026 00:00:00 GMT");
        assertThat(result.contentType()).startsWith("application/rss+xml");
        assertThat(result.finalUri()).isEqualTo(server.uri("/feed.xml"));
        StubHttpServer.Recorded request = server.requests().getFirst();
        assertThat(request.header("Accept-Encoding")).isNull();
        assertThat(request.header("User-Agent")).isEqualTo("java21-blog-feed/1.0 (+https://blog.java21.net/updates)");
        assertThat(request.header("Accept")).contains("application/rss+xml");
    }

    @Test
    void conditionalRequestAnd304() {
        server.handle("/feed.xml", exchange -> {
            boolean match = "\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.sendResponseHeaders(match ? 304 : 200, -1);
            exchange.close();
        });

        FetchResult result = fetcher.fetch(SafeHttpFetcher.Request.get(server.uri("/feed.xml"), SafeHttpFetcher.Limit.FEED)
                .conditional("\"v1\"", "Mon, 05 Oct 2026 00:00:00 GMT"));

        assertThat(result.notModified()).isTrue();
        assertThat(result.body()).isNull();
        assertThat(server.requests().getFirst().header("If-Modified-Since")).isEqualTo("Mon, 05 Oct 2026 00:00:00 GMT");
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 303, 307, 308})
    void followsRedirects(int status) {
        server.redirect("/old", status, "/new");
        server.respond("/new", 200, "text/xml", "<rss/>");

        FetchResult result = fetcher.get(server.uri("/old"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.finalUri()).isEqualTo(server.uri("/new"));
    }

    @Test
    void atMostThreeRedirects() {
        server.redirect("/r1", 302, "/r2");
        server.redirect("/r2", 302, "/r3");
        server.redirect("/r3", 302, "/r4");
        server.respond("/r4", 200, "text/xml", "<rss/>");
        assertThat(fetcher.get(server.uri("/r1"), SafeHttpFetcher.Limit.FEED).isSuccess()).isTrue();

        server.redirect("/r0", 302, "/r1");
        FetchResult result = fetcher.get(server.uri("/r0"), SafeHttpFetcher.Limit.FEED);
        assertThat(result.failure()).isEqualTo(FetchFailure.HTTP_ERROR);
        assertThat(result.status()).isEqualTo(302);
    }

    @Test
    void redirectToInternalAddressIsNotRequested() {
        server.redirect("/go", 302, "http://internal.test:" + server.port() + "/admin");

        FetchResult result = fetcher.get(server.uri("/go"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.failure()).isEqualTo(FetchFailure.BLOCKED_ADDRESS);
        assertThat(result.blockReason()).isEqualTo(BlockReason.PRIVATE_ADDRESS);
        assertThat(server.requests()).extracting(StubHttpServer.Recorded::path).containsExactly("/go");
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://public.test/feed"})
    void redirectToOtherSchemeIsBlocked(String location) {
        server.redirect("/go", 302, location);

        FetchResult result = fetcher.get(server.uri("/go"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.failure()).isEqualTo(FetchFailure.BLOCKED_ADDRESS);
        assertThat(result.blockReason()).isEqualTo(BlockReason.SCHEME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://blog.java21.net/rss", "https://www.blog.java21.net/x", "http://BLOG.java21.net./",
            "http://localhost.example/feed"})
    void ourOwnHostIsSelf(String url) {
        assertThat(fetcher.check(URI.create(url))).isEqualTo(BlockReason.SELF);
        assertThat(fetcher.get(URI.create(url), SafeHttpFetcher.Limit.FEED).blockReason()).isEqualTo(BlockReason.SELF);
        assertThat(fetcher.isSelf("notblog.java21.net.evil")).isFalse();
        assertThat(fetcher.isSelf("evilblog.java21.net")).isFalse();
    }

    @Test
    void redirectToOurOwnHostIsSelf() {
        server.redirect("/go", 301, "https://blog.java21.net/api/v1/me");

        FetchResult result = fetcher.get(server.uri("/go"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.blockReason()).isEqualTo(BlockReason.SELF);
    }

    @Test
    void checkReasons() {
        assertThat(fetcher.check(URI.create("http://user:pw@public.test/"))).isEqualTo(BlockReason.CREDENTIALS);
        assertThat(fetcher.check(URI.create("gopher://public.test/"))).isEqualTo(BlockReason.SCHEME);
        assertThat(fetcher.check(URI.create("http://public.test:22/"))).isEqualTo(BlockReason.PORT);
        assertThat(fetcher.check(URI.create("http://internal.test/"))).isEqualTo(BlockReason.PRIVATE_ADDRESS);
        assertThat(fetcher.check(URI.create("http://2130706433/"))).isEqualTo(BlockReason.INVALID_URL);
        assertThat(fetcher.check(URI.create("http:///nohost"))).isEqualTo(BlockReason.INVALID_URL);
        assertThat(fetcher.check(URI.create("http://public.test/feed"))).isNull();
        // 이름 해석 실패는 주소 형식 문제가 아니다(요청하면 DNS_ERROR).
        assertThat(fetcher.check(URI.create("http://nowhere.test/feed"))).isNull();
    }

    @Test
    void declaredLengthOverLimitIsNotRead() {
        server.respondBytes("/big", 200, "text/xml", new byte[2048], Map.of());

        FetchResult result = fetcher.get(server.uri("/big"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.failure()).isEqualTo(FetchFailure.TOO_LARGE);
    }

    @Test
    void streamedBodyOverLimitIsCut() {
        server.handle("/stream", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/xml");
            exchange.sendResponseHeaders(200, 0); // 길이 없이 흘려보냄
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 64; i++) {
                    out.write(new byte[256]);
                    out.flush();
                }
            } catch (java.io.IOException ignored) {
                // 받는 쪽이 끊으면 쓰기가 실패한다.
            }
        });

        FetchResult result = fetcher.get(server.uri("/stream"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.failure()).isEqualTo(FetchFailure.TOO_LARGE);
    }

    @Test
    void imageAndPageLimitsDiffer() {
        server.respondBytes("/img", 200, "image/png", new byte[2000], Map.of());
        server.respondBytes("/page", 200, "text/html", new byte[2000], Map.of());

        assertThat(fetcher.get(server.uri("/img"), SafeHttpFetcher.Limit.IMAGE).isSuccess()).isTrue();
        assertThat(fetcher.get(server.uri("/page"), SafeHttpFetcher.Limit.PAGE).failure()).isEqualTo(FetchFailure.TOO_LARGE);
    }

    @Test
    void slowResponseTimesOut() {
        // 요청 10초 규칙을 시험 시간을 줄이려고 300ms로 줄여 같은 경로를 확인한다.
        SafeHttpFetcher quick = fetcher(Duration.ofMillis(300), 1024);
        server.respond("/slow", 200, "text/xml", "<rss/>", Duration.ofMillis(1500));

        FetchResult result = quick.get(server.uri("/slow"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.failure()).isEqualTo(FetchFailure.TIMEOUT);
    }

    @Test
    void unknownHostIsDnsError() {
        FetchResult result = fetcher.get(URI.create("http://nowhere.test/feed"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.failure()).isEqualTo(FetchFailure.DNS_ERROR);
        assertThat(result.httpStatusOrNull()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 500})
    void errorStatusIsHttpError(int status) {
        server.respond("/feed", status, "text/plain", "x");

        FetchResult result = fetcher.get(server.uri("/feed"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.failure()).isEqualTo(FetchFailure.HTTP_ERROR);
        assertThat(result.httpStatusOrNull()).isEqualTo(status);
    }

    @Test
    void headAndRangeDoNotReadBody() {
        server.respondBytes("/post", 200, "text/html", new byte[2000], Map.of());

        FetchResult head = fetcher.fetch(SafeHttpFetcher.Request.head(server.uri("/post")));
        FetchResult range = fetcher.fetch(new SafeHttpFetcher.Request(server.uri("/post"), "GET",
                SafeHttpFetcher.Limit.NONE, null, null, null, null).withRange("bytes=0-0"));

        assertThat(head.isSuccess()).isTrue();
        assertThat(head.body()).isNull();
        assertThat(range.isSuccess()).isTrue();
        assertThat(range.body()).isNull();
        assertThat(server.requests()).extracting(StubHttpServer.Recorded::method).containsExactly("HEAD", "GET");
        assertThat(server.requests().get(1).header("Range")).isEqualTo("bytes=0-0");
    }

    @Test
    void connectionRefusedIsHttpError() {
        int port = server.port();
        server.close();
        SafeHttpFetcher closed = fetcher(Duration.ofSeconds(2), 1024);

        FetchResult result = closed.get(URI.create("http://127.0.0.1:" + port + "/feed"), SafeHttpFetcher.Limit.FEED);

        assertThat(result.isSuccess()).isFalse();
        server = StubHttpServer.start();
    }

    @Test
    void settingsFromProperties() {
        SafeHttpFetcher.Settings settings = ExternalFetchConfig.settings(ExternalFeedProperties.defaults(),
                "https://example.org/");

        assertThat(settings.selfHosts()).containsExactlyInAnyOrder("blog.java21.net", "example.org");
        assertThat(settings.maxFeedBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(settings.maxPageBytes()).isEqualTo(1024L * 1024);
        assertThat(settings.maxImageBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.userAgent()).isEqualTo("java21-blog-feed/1.0 (+https://example.org/updates)");
    }
}
