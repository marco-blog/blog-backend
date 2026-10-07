package net.java21.blog.backend.external.member;

import static net.java21.blog.backend.support.ExternalTestKit.rss;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.feed.FeedDiscovery;
import net.java21.blog.backend.external.feed.FeedFormat;
import net.java21.blog.backend.external.feed.FeedParser;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.external.member.dto.FeedPreviewResponse;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.report.ReportsProperties;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.spam.RateLimitPolicy;
import net.java21.blog.backend.spam.RateLimiter;
import net.java21.blog.backend.support.ExternalTestKit;
import net.java21.blog.backend.support.StubHttpServer;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 007 T023: 미리보기와 피드 찾기(US1 AS1, FR-109, research E3). 외부 요청은 모두 {@link StubHttpServer}로. */
class PreviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final String RSS = "application/rss+xml; charset=UTF-8";

    private StubHttpServer server;
    private ExternalBlogRepository blogRepository;
    private PreviewService service;
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
        blogRepository = mock(ExternalBlogRepository.class);
        when(blogRepository.findHolding(anyString())).thenReturn(Optional.empty());
        service = service(ExternalTestKit.properties("preview-per-hour", "3"));
    }

    private PreviewService service(ExternalFeedProperties props) {
        FeedDiscovery discovery = new FeedDiscovery(ExternalTestKit.fetcher(server), new FeedParser(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        RateLimitPolicy policy = new RateLimitPolicy(new RateLimiter(ticker), mock(SystemSettingsService.class),
                ReportsProperties.defaults(), TrackbackProperties.defaults(), props);
        return new PreviewService(discovery, blogRepository, policy, ticker);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String site() {
        return server.uri("/").toString();
    }

    private String feed(int count) {
        String[][] items = new String[count][];
        for (int i = 0; i < count; i++) {
            items[i] = new String[] {"g" + i, "Post " + i, site() + "posts/" + i, "Mon, 05 Oct 2026 0" + i + ":00:00 GMT",
                    "<p>body " + i + "</p>"};
        }
        return rss("Dev Log", site(), "about", items);
    }

    @Test
    void feedUrlDirectlyGivesThreeRecentPostsInFeedOrder() {
        server.respond("/feed.xml", 200, RSS, feed(5));

        FeedPreviewResponse preview = service.preview(1L, server.uri("/feed.xml").toString());

        assertThat(preview.feedUrl()).isEqualTo(server.uri("/feed.xml").toString());
        assertThat(preview.title()).isEqualTo("Dev Log");
        assertThat(preview.format()).isEqualTo(FeedFormat.RSS);
        assertThat(preview.recentPosts()).extracting(FeedPreviewResponse.RecentPost::title)
                .containsExactly("Post 0", "Post 1", "Post 2");
        assertThat(preview.registered()).isNull();
    }

    @Test
    void blogHtmlFindsAlternateLink() {
        server.respond("/", 200, "text/html; charset=UTF-8",
                "<html><head><link rel=\"alternate\" type=\"application/atom+xml\" href=\"/atom.xml\"></head></html>");
        server.respond("/atom.xml", 200, "application/atom+xml",
                "<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Atom Log</title>"
                        + "<id>urn:x</id><updated>2026-10-01T00:00:00Z</updated><entry><title>A1</title>"
                        + "<link rel=\"alternate\" href=\"" + site() + "a1\"/><id>urn:a1</id>"
                        + "<updated>2026-10-01T00:00:00Z</updated></entry></feed>");

        FeedPreviewResponse preview = service.preview(1L, site());

        assertThat(preview.feedUrl()).isEqualTo(server.uri("/atom.xml").toString());
        assertThat(preview.format()).isEqualTo(FeedFormat.ATOM);
        assertThat(preview.recentPosts()).hasSize(1);
    }

    @Test
    void withoutLinkTriesRssThenFeedThenNotFound() {
        server.respond("/blog", 200, "text/html", "<html><body>no feed</body></html>");

        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.preview(1L, server.uri("/blog").toString()));

        assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_FEED_NOT_FOUND);
        assertThat(e.params()).containsEntry("tried", 3);
        assertThat(server.requests()).extracting(StubHttpServer.Recorded::path)
                .containsExactly("/blog", "/rss", "/feed");
    }

    @Test
    void guessedFeedPathIsUsed() {
        server.respond("/blog", 200, "text/html", "<html><body>no feed link</body></html>");
        server.respond("/feed", 200, RSS, feed(1));

        FeedPreviewResponse preview = service.preview(1L, server.uri("/blog").toString());

        assertThat(preview.feedUrl()).isEqualTo(server.uri("/feed").toString());
    }

    @Test
    void redirectFinalAddressIsFeedUrl() {
        server.redirect("/old.xml", 301, "/new.xml");
        server.respond("/new.xml", 200, RSS, feed(1));

        assertThat(service.preview(1L, server.uri("/old.xml").toString()).feedUrl())
                .isEqualTo(server.uri("/new.xml").toString());
    }

    @Test
    void registeredFeedReportsStatusClaimableAndMine() {
        server.respond("/feed.xml", 200, RSS, feed(1));
        User owner = TestEntities.user(7L);
        ExternalBlog blog = ExternalBlog.memberRequest(owner, server.uri("/feed.xml").toString(), "h",
                TestEntities.topic(3L, null, "t"));
        TestEntities.with(blog, "id", 42L);
        when(blogRepository.findHolding(FeedUrlNormalizer.hash(server.uri("/feed.xml").toString())))
                .thenReturn(Optional.of(blog));

        FeedPreviewResponse.Registered other = service.preview(1L, server.uri("/feed.xml").toString()).registered();
        assertThat(other).isEqualTo(new FeedPreviewResponse.Registered(42L, ExternalBlogStatus.PENDING, true, false));
        assertThat(service.preview(7L, server.uri("/feed.xml").toString()).registered().mine()).isTrue();

        blog.block();
        assertThat(service.preview(1L, server.uri("/feed.xml").toString()).registered().claimable()).isFalse();
    }

    @Test
    void sameFeedWithinTenMinutesIsCached() {
        server.respond("/feed.xml", 200, RSS, feed(1));
        service.preview(1L, server.uri("/feed.xml").toString());
        service.preview(1L, server.uri("/feed.xml").toString());
        assertThat(server.requests()).hasSize(1);

        nanos.addAndGet(TimeUnit.MINUTES.toNanos(11));
        service.preview(2L, server.uri("/feed.xml").toString());
        assertThat(server.requests()).hasSize(2);
    }

    @Test
    void hourlyLimitPerMember() {
        server.respond("/feed.xml", 200, RSS, feed(1));
        for (int i = 0; i < 3; i++) {
            service.preview(1L, server.uri("/feed.xml").toString());
        }
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.preview(1L, server.uri("/feed.xml").toString()));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        service.preview(2L, server.uri("/feed.xml").toString());
        nanos.addAndGet(Duration.ofHours(1).toNanos());
        service.preview(1L, server.uri("/feed.xml").toString());
    }

    @Test
    void ourServiceAndBadAddressesAreRefused() {
        assertReason("https://blog.java21.net/marco/rss", "SELF");
        assertReason("https://sub.blog.java21.net/feed", "SELF");
        assertReason("ftp://example.com/feed", "SCHEME");
        assertReason("http://user@127.0.0.1:" + server.port() + "/feed", "CREDENTIALS");
        assertReason("http://internal.test/feed", "PRIVATE_ADDRESS");
        assertReason("http://127.0.0.1:1/feed", "PORT");
        assertReason("http://exa mple.com:99999999/", "INVALID_URL");
        BusinessException empty = catchThrowableOfType(BusinessException.class, () -> service.preview(1L, " "));
        assertThat(empty.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        BusinessException tooLong = catchThrowableOfType(BusinessException.class,
                () -> service.preview(1L, "https://a.example/" + "x".repeat(1000)));
        assertThat(tooLong.fieldErrors()).extracting(f -> f.code()).containsExactly("TOO_LONG");
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void unreadableFeedReportsResult() {
        server.respond("/broken.xml", 200, "application/rss+xml", "<rss><channel>");
        server.respond("/gone.xml", 500, "text/plain", "boom");

        BusinessException parse = catchThrowableOfType(BusinessException.class,
                () -> service.preview(1L, server.uri("/broken.xml").toString()));
        assertThat(parse.errorCode()).isEqualTo(ErrorCode.EXTERNAL_FEED_UNREADABLE);
        assertThat(parse.params()).containsEntry("result", "PARSE_ERROR");

        BusinessException http = catchThrowableOfType(BusinessException.class,
                () -> service.preview(1L, server.uri("/gone.xml").toString()));
        assertThat(http.params()).containsEntry("result", "HTTP_ERROR").containsEntry("httpStatus", 500);
    }

    @Test
    void htmlLinkToBrokenFeedIsUnreadable() {
        server.respond("/", 200, "text/html",
                "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/x.xml\"></head></html>");
        server.respond("/x.xml", 200, "text/plain", "not a feed");

        BusinessException e = catchThrowableOfType(BusinessException.class, () -> service.preview(1L, site()));
        assertThat(e.params()).containsEntry("result", "PARSE_ERROR");
    }

    @Test
    void redirectToOurServiceIsRefused() {
        server.redirect("/moved", 302, "https://blog.java21.net/marco/rss");
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.preview(1L, server.uri("/moved").toString()));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_FEED_URL_NOT_ALLOWED);
        assertThat(e.params()).containsEntry("reason", "SELF");
    }

    private void assertReason(String url, String reason) {
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> service.preview(1L, url));
        assertThat(e).as(url).isNotNull();
        assertThat(e.errorCode()).as(url).isEqualTo(ErrorCode.EXTERNAL_FEED_URL_NOT_ALLOWED);
        assertThat(e.params()).as(url).containsEntry("reason", reason);
    }
}
