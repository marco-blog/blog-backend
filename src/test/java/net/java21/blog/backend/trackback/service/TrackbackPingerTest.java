package net.java21.blog.backend.trackback.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.net.HostResolver;
import net.java21.blog.backend.common.net.OutboundProperties;
import net.java21.blog.backend.common.net.OutboundUrlGuard;
import net.java21.blog.backend.support.StubHttpServer;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.trackback.domain.PingErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 밖으로 트랙백 보내기(005 T090, FR-052, research M15·M16, Edge Cases). 루프백 흉내 서버이므로 {@code allow-private=true}. */
class TrackbackPingerTest {

    private static final String XML = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";
    private static final TrackbackPinger.Payload PAYLOAD = new TrackbackPinger.Payload(
            "https://blog.java21.net/marco/42", "보낸 글 & 제목", "요약 ".repeat(100), "마르코 블로그");

    private StubHttpServer server;
    private TrackbackPinger pinger;

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
        pinger = pinger(true, Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private TrackbackPinger pinger(boolean allowPrivate, Duration readTimeout) {
        OutboundUrlGuard guard = new OutboundUrlGuard(new OutboundProperties(List.of(server.port(), 80, 443),
                allowPrivate), HostResolver.system());
        TrackbackProperties defaults = TrackbackProperties.defaults();
        return new TrackbackPinger(guard, new TrackbackProperties(defaults.receiveLimit(), defaults.receiveWindow(),
                Duration.ofSeconds(1), readTimeout, defaults.maxTargets(), defaults.executorThreads(),
                defaults.executorQueue(), defaults.recoverPendingAfter()));
    }

    private String url(String path) {
        return server.uri(path).toString();
    }

    private static Map<String, String> decode(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            form.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return form;
    }

    @Test
    void sendsUtf8FormAndErrorZeroIsSuccess() {
        server.respond("/tb", 200, "text/xml", XML + "<response><error>0</error></response>");

        TrackbackPinger.Result result = pinger.ping(url("/tb"), PAYLOAD);

        assertThat(result.success()).isTrue();
        assertThat(server.requests()).singleElement().satisfies(r -> {
            assertThat(r.method()).isEqualTo("POST");
            assertThat(r.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded; charset=utf-8");
            assertThat(r.header("User-Agent")).isEqualTo("blog.java21.net-trackback/1.0");
            Map<String, String> form = decode(r.body());
            assertThat(form).containsEntry("url", "https://blog.java21.net/marco/42")
                    .containsEntry("title", "보낸 글 & 제목")
                    .containsEntry("blog_name", "마르코 블로그");
            assertThat(form.get("excerpt")).hasSizeBetween(250, 255).startsWith("요약 요약");
        });
    }

    @Test
    void errorOneIsRemoteErrorWithCleanedMessage() {
        server.respond("/tb", 200, "text/xml", XML + "<response><error>1</error><message>"
                + "&lt;b&gt;Spam&lt;/b&gt; detected " + "x".repeat(400) + "</message></response>");

        TrackbackPinger.Result result = pinger.ping(url("/tb"), PAYLOAD);

        assertThat(result.code()).isEqualTo(PingErrorCode.REMOTE_ERROR);
        assertThat(result.message()).startsWith("Spam detected").hasSize(255);
    }

    @Test
    void errorOneWithoutMessageAndBodiesWithoutErrorAreRemoteErrors() {
        server.respond("/no-message", 200, "text/xml", "<response><error>1</error></response>");
        server.respond("/html", 200, "text/html", "<html><body>Hello</body></html>");
        server.respond("/text", 200, "text/plain", "OK");

        assertThat(pinger.ping(url("/no-message"), PAYLOAD))
                .isEqualTo(new TrackbackPinger.Result(PingErrorCode.REMOTE_ERROR, "Remote error"));
        assertThat(pinger.ping(url("/html"), PAYLOAD))
                .isEqualTo(new TrackbackPinger.Result(PingErrorCode.REMOTE_ERROR, "Invalid response"));
        assertThat(pinger.ping(url("/text"), PAYLOAD))
                .isEqualTo(new TrackbackPinger.Result(PingErrorCode.REMOTE_ERROR, "Invalid response"));
    }

    @Test
    void warningTextBeforeXmlStillReadsTheErrorTag() {
        server.respond("/tb", 200, "text/xml", "Warning: something\n" + XML + "<response><error>0</error></response>");

        assertThat(pinger.ping(url("/tb"), PAYLOAD).success()).isTrue();
    }

    @Test
    void non2xxIsHttpErrorWithStatus() {
        server.respond("/tb", 500, "text/xml", XML + "<response><error>0</error></response>");

        assertThat(pinger.ping(url("/tb"), PAYLOAD))
                .isEqualTo(new TrackbackPinger.Result(PingErrorCode.HTTP_ERROR, "HTTP 500"));
    }

    @Test
    void redirectsAreNotFollowed() {
        server.respond("/moved", 302, null, null);
        server.respond("/target", 200, "text/xml", XML + "<response><error>0</error></response>");

        // 302에 Location이 없어도 따라가지 않는다는 것만 보면 된다(상태 코드가 결과).
        assertThat(pinger.ping(url("/moved"), PAYLOAD))
                .isEqualTo(new TrackbackPinger.Result(PingErrorCode.HTTP_ERROR, "HTTP 302"));
        assertThat(server.requests()).extracting(StubHttpServer.Recorded::path).containsExactly("/moved");
    }

    @Test
    void slowResponseTimesOut() {
        server.respond("/slow", 200, "text/xml", XML + "<response><error>0</error></response>",
                Duration.ofSeconds(3));

        assertThat(pinger.ping(url("/slow"), PAYLOAD).code()).isEqualTo(PingErrorCode.TIMEOUT);
    }

    @Test
    void defaultTimeoutsAreFiveSeconds() {
        assertThat(TrackbackProperties.defaults().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(TrackbackProperties.defaults().readTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void readsOnlyTheFirst64Kilobytes() {
        server.respond("/big", 200, "text/xml", XML + "<response><error>0</error><message>"
                + "a".repeat(200_000) + "</message></response>");
        server.respond("/late", 200, "text/xml", XML + "<response><message>" + "a".repeat(70_000)
                + "</message><error>0</error></response>");

        assertThat(pinger.ping(url("/big"), PAYLOAD).success()).as("잘린 앞부분의 error 0").isTrue();
        assertThat(pinger.ping(url("/late"), PAYLOAD).code()).as("64KB 뒤의 error는 읽지 않음")
                .isEqualTo(PingErrorCode.REMOTE_ERROR);
    }

    @Test
    void doctypeAndExternalEntitiesAreNeverResolved() {
        server.respond("/xxe", 200, "text/plain", "leaked");
        server.respond("/tb", 200, "text/xml", XML + "<!DOCTYPE r [<!ENTITY net SYSTEM \"" + url("/xxe")
                + "\"><!ENTITY file SYSTEM \"file:///etc/hostname\">]>"
                + "<response><error>0</error><message>&net;&file;</message></response>");

        TrackbackPinger.Result result = pinger.ping(url("/tb"), PAYLOAD);

        assertThat(result).isEqualTo(new TrackbackPinger.Result(PingErrorCode.REMOTE_ERROR, "Invalid response"));
        assertThat(server.requests()).extracting(StubHttpServer.Recorded::path).containsExactly("/tb");
    }

    @Test
    void internalAddressesAreBlockedWithoutRequest() {
        TrackbackPinger strict = pinger(false, Duration.ofSeconds(1));
        server.respond("/tb", 200, "text/xml", "<response><error>0</error></response>");

        assertThat(strict.ping(url("/tb"), PAYLOAD).code()).isEqualTo(PingErrorCode.BLOCKED_ADDRESS);
        assertThat(strict.ping("http://127.0.0.1:22/tb", PAYLOAD).code()).isEqualTo(PingErrorCode.BLOCKED_ADDRESS);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void malformedOrUnresolvableUrlsAreInvalid() {
        assertThat(pinger.ping("http://exa mple.com/", PAYLOAD).code()).isEqualTo(PingErrorCode.INVALID_URL);
        assertThat(pinger.ping("ftp://example.com/", PAYLOAD).code()).isEqualTo(PingErrorCode.INVALID_URL);
        assertThat(pinger.ping("http://no-such-host.invalid/tb", PAYLOAD).code())
                .isEqualTo(PingErrorCode.INVALID_URL);
    }

    @Test
    void connectionRefusedIsHttpError() {
        int port = server.port();
        server.close();
        TrackbackPinger.Result result = pinger.ping("http://127.0.0.1:" + port + "/tb", PAYLOAD);
        assertThat(result.code()).isIn(PingErrorCode.HTTP_ERROR, PingErrorCode.TIMEOUT);
        server = StubHttpServer.start();
    }
}
