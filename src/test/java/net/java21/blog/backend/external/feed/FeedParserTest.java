package net.java21.blog.backend.external.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** 007 T009: 피드 읽기(research E3·E4, FR-114, SC-021). 고정 데이터는 src/test/resources/external/feeds/. */
class FeedParserTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private final FeedParser parser = new FeedParser();

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = FeedParserTest.class.getResourceAsStream("/external/feeds/" + name)) {
            return in.readAllBytes();
        }
    }

    private ParsedFeed parse(String name, String feedUrl) throws Exception {
        return parser.parse(fixture(name), "application/xml", URI.create(feedUrl), NOW, 100);
    }

    @Test
    void rss20() throws Exception {
        ParsedFeed feed = parse("rss20.xml", "https://marco.example/rss");

        assertThat(feed.format()).isEqualTo(FeedFormat.RSS);
        assertThat(feed.title()).isEqualTo("Marco Dev Notes");
        assertThat(feed.siteUrl()).isEqualTo("https://marco.example/");
        assertThat(feed.items()).hasSize(3); // javascript: 항목은 버림

        FeedItem first = feed.items().getFirst();
        assertThat(first.guid()).isEqualTo("post-1");
        assertThat(first.link()).isEqualTo("https://marco.example/posts/1");
        assertThat(first.title()).isEqualTo("Spring Boot 4 released");
        assertThat(first.summary()).isEqualTo("Hello world & friends");
        assertThat(first.imageUrl()).isEqualTo("https://marco.example/img/1.png");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-05T10:00:00Z"));
        assertThat(first.categories()).containsExactly("Java", "Spring");

        FeedItem second = feed.items().get(1);
        assertThat(second.guid()).isNull();
        assertThat(second.title()).isEqualTo("second-post");

        FeedItem future = feed.items().get(2);
        assertThat(future.publishedAt()).isEqualTo(NOW);
        assertThat(future.summary()).isEqualTo("Only content");
        assertThat(future.imageUrl()).isEqualTo("https://cdn.example/real.jpg"); // 1x1 추적 픽셀 건너뜀
    }

    @Test
    void rss091AndRdf() throws Exception {
        ParsedFeed old = parse("rss091.xml", "http://old.example/rss");
        assertThat(old.format()).isEqualTo(FeedFormat.RSS);
        assertThat(old.items()).singleElement().satisfies(i -> {
            assertThat(i.title()).isEqualTo("First 091");
            assertThat(i.guid()).isNull();
            assertThat(i.publishedAt()).isNull();
        });

        ParsedFeed rdf = parse("rdf10.xml", "http://rdf.example/index.rdf");
        assertThat(rdf.format()).isEqualTo(FeedFormat.RSS);
        assertThat(rdf.items()).singleElement().satisfies(i -> {
            assertThat(i.link()).isEqualTo("http://rdf.example/a");
            assertThat(i.publishedAt()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        });
    }

    @Test
    void atom10() throws Exception {
        ParsedFeed feed = parse("atom10.xml", "https://atom.example/atom.xml");

        assertThat(feed.format()).isEqualTo(FeedFormat.ATOM);
        assertThat(feed.title()).isEqualTo("Atom Blog");
        assertThat(feed.siteUrl()).isEqualTo("https://atom.example/");
        FeedItem first = feed.items().getFirst();
        assertThat(first.guid()).isEqualTo("urn:uuid:entry-1");
        assertThat(first.link()).isEqualTo("https://atom.example/entry/1");
        assertThat(first.summary()).isEqualTo("Atom summary");
        assertThat(first.categories()).containsExactly("kubernetes");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2026-10-03T00:00:00Z")); // 발행 없으면 수정 시각
        assertThat(feed.items().get(1).summary()).isEqualTo("content only");
    }

    @Test
    void eucKr() throws Exception {
        ParsedFeed feed = parser.parse(fixture("euc-kr.xml"), "text/xml", URI.create("http://kr.example/rss"), NOW, 100);

        assertThat(feed.title()).isEqualTo("한글 블로그");
        assertThat(feed.items().getFirst().title()).isEqualTo("첫 번째 글");
        assertThat(feed.items().getFirst().summary()).isEqualTo("요약 문장입니다");
    }

    @Test
    void doctypeIsRejectedWithoutReadingEntities() throws Exception {
        byte[] xxe = fixture("doctype-xxe.xml");

        assertThatThrownBy(() -> parser.parse(xxe, "application/xml", URI.create("http://x.example/rss"), NOW, 100))
                .isInstanceOf(FeedParseException.class);
        assertThatThrownBy(() -> parser.containsText(xxe, null, "x")).isInstanceOf(FeedParseException.class);
    }

    @Test
    void notAFeed() {
        assertThatThrownBy(() -> parser.parse("<html><body>hi</body></html>".getBytes(), "text/html",
                URI.create("http://x.example/"), NOW, 100)).isInstanceOf(FeedParseException.class);
        assertThatThrownBy(() -> parser.parse(new byte[0], null, URI.create("http://x.example/"), NOW, 100))
                .isInstanceOf(FeedParseException.class);
        assertThatThrownBy(() -> parser.parse("{\"version\":\"https://jsonfeed.org/version/1\"}".getBytes(),
                "application/json", URI.create("http://x.example/"), NOW, 100)).isInstanceOf(FeedParseException.class);
    }

    @Test
    void mediaRssImageOrder() throws Exception {
        ParsedFeed feed = parse("media-rss.xml", "https://media.example/rss");

        assertThat(feed.items()).extracting(FeedItem::imageUrl).containsExactly(
                "https://media.example/enc.jpg",
                "https://media.example/thumb2.jpg",
                "https://media.example/content3.png",
                "https://media.example/body4.jpg");
    }

    @Test
    void missingDatesAndTitles() throws Exception {
        ParsedFeed feed = parse("no-dates.xml", "https://nodates.example/feed");

        assertThat(feed.title()).isEqualTo("No dates");
        assertThat(feed.siteUrl()).isEqualTo("https://nodates.example/");
        assertThat(feed.items().getFirst().title()).isEqualTo("b");
        assertThat(feed.items().getFirst().publishedAt()).isNull();
        assertThat(feed.items().get(1).link()).isEqualTo("https://nodates.example/permalink");
        assertThat(feed.items().get(1).guid()).isEqualTo("https://nodates.example/permalink");
    }

    @Test
    void bigContentKeepsOnlySummary() throws Exception {
        ParsedFeed feed = parse("big-content.xml", "https://big.example/rss");

        assertThat(feed.items()).hasSize(1); // 2000자 넘는 링크는 버림
        FeedItem item = feed.items().getFirst();
        assertThat(item.title()).hasSize(300);
        assertThat(item.summary().codePointCount(0, item.summary().length())).isEqualTo(200);
        assertThat(item.categories()).hasSize(20);
        assertThat(item.categories().getFirst()).hasSize(100);
        // 결과 객체에 본문 문자열 필드가 없다(SC-021).
        assertThat(Arrays.stream(FeedItem.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("guid", "link", "title", "summary", "imageUrl", "publishedAt", "categories");
        assertThat(item.toString().length()).isLessThan(2000);
    }

    @Test
    void maxItems() throws Exception {
        ParsedFeed feed = parser.parse(fixture("rss20.xml"), null, URI.create("https://marco.example/rss"), NOW, 1);
        assertThat(feed.items()).hasSize(1);
    }

    @Test
    void containsTextLooksAtChannelAndItems() throws Exception {
        byte[] rss = fixture("rss20.xml");
        assertThat(parser.containsText(rss, null, "Spring and Java")).isTrue();
        assertThat(parser.containsText(rss, null, "friends")).isTrue();
        assertThat(parser.containsText(rss, null, "Only content")).isTrue();
        assertThat(parser.containsText(rss, null, "java21-verify-XXXX")).isFalse();
        assertThat(parser.containsText(rss, null, "alert(1)")).isFalse(); // script 안 텍스트는 보지 않음
    }
}
