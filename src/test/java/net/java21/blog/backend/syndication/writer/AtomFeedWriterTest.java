package net.java21.blog.backend.syndication.writer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import com.rometools.rome.feed.atom.Entry;
import com.rometools.rome.feed.atom.Feed;
import com.rometools.rome.feed.atom.Link;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.WireFeedInput;

import org.jdom2.Element;
import org.jdom2.Namespace;

import org.junit.jupiter.api.Test;

/**
 * Atom 1.0 작성(T085, RFC 4287, SC-008): ROME으로 다시 읽어 {@code id}·{@code updated}(RFC 3339)·{@code author/name}·
 * {@code link rel=self/alternate}, 항목 {@code content type=html}(FULL) 또는 {@code summary}(SUMMARY), {@code category term},
 * 이스케이프, 빈 피드도 유효.
 */
class AtomFeedWriterTest {

    private static final Namespace ATOM = Namespace.getNamespace("http://www.w3.org/2005/Atom");

    private final AtomFeedWriter writer = new AtomFeedWriter();

    @Test
    void feedHasIdUpdatedAuthorAndLinks() throws Exception {
        byte[] xml = writer.write(FeedFixtures.feed(List.of(FeedFixtures.full(2))));

        Feed feed = (Feed) new WireFeedInput().build(RssFeedWriterTest.reader(xml));
        assertThat(feed.getFeedType()).isEqualTo("atom_1.0");
        assertThat(feed.getId()).isEqualTo("https://blog.java21.net/marco/atom");
        assertThat(feed.getTitleEx().getValue()).isEqualTo("마르코 & <블로그> 😀");
        assertThat(feed.getSubtitle().getValue()).isEqualTo("자바 & 스프링");
        assertThat(feed.getUpdated()).isEqualTo(Date.from(FeedFixtures.UPDATED));
        assertThat(feed.getAuthors()).extracting(p -> p.getName()).containsExactly("마르코");
        assertThat(feed.getAuthors().getFirst().getEmail()).isNull();
        assertThat(feed.getAlternateLinks()).extracting(Link::getHref).containsExactly("https://blog.java21.net/marco");
        assertThat(feed.getOtherLinks()).singleElement().satisfies(link -> {
            assertThat(link.getRel()).isEqualTo("self");
            assertThat(link.getHref()).isEqualTo("https://blog.java21.net/marco/atom");
            assertThat(link.getType()).isEqualTo("application/atom+xml");
        });
        Element root = RssFeedWriterTest.dom(xml).getRootElement();
        assertThat(root.getNamespace()).isEqualTo(ATOM);
        assertThat(root.getChildText("updated", ATOM)).isEqualTo("2026-10-07T01:02:03Z");
        assertThat(new String(xml, StandardCharsets.UTF_8)).doesNotContain("<email>");
    }

    @Test
    void fullEntryHasHtmlContentAndCategories() throws Exception {
        byte[] xml = writer.write(FeedFixtures.feed(List.of(FeedFixtures.full(2), FeedFixtures.full(1))));

        Feed feed = (Feed) new WireFeedInput().build(RssFeedWriterTest.reader(xml));
        Entry entry = feed.getEntries().getFirst();
        assertThat(entry.getId()).isEqualTo("https://blog.java21.net/marco/2");
        assertThat(entry.getTitleEx().getValue()).isEqualTo("글 2 <&> 🎉");
        assertThat(entry.getAlternateLinks()).extracting(Link::getHref).containsExactly("https://blog.java21.net/marco/2");
        assertThat(entry.getPublished()).isEqualTo(Date.from(FeedFixtures.PUBLISHED));
        assertThat(entry.getUpdated()).isEqualTo(Date.from(FeedFixtures.UPDATED));
        assertThat(entry.getContents()).singleElement().satisfies(content -> {
            assertThat(content.getType()).isEqualTo("html");
            assertThat(content.getValue()).isEqualTo("<p>본문 <img src=\"https://blog.java21.net/media/k\"></p>");
        });
        assertThat(entry.getSummary()).isNull();
        assertThat(entry.getCategories()).extracting(c -> c.getTerm()).containsExactly("Spring", "java");
        assertThat(feed.getEntries()).extracting(Entry::getId).doesNotHaveDuplicates();
        Element rawEntry = RssFeedWriterTest.dom(xml).getRootElement().getChild("entry", ATOM);
        assertThat(rawEntry.getChildText("published", ATOM)).isEqualTo("2026-10-06T04:24:19Z");
    }

    @Test
    void summaryAndTitleOnlyEntries() throws Exception {
        byte[] xml = writer.write(FeedFixtures.feed(List.of(FeedFixtures.summary(2), FeedFixtures.titleOnly(1))));

        Feed feed = (Feed) new WireFeedInput().build(RssFeedWriterTest.reader(xml));
        Entry summary = feed.getEntries().getFirst();
        assertThat(summary.getContents()).isEmpty();
        assertThat(summary.getSummary().getValue()).isEqualTo("요약 <b>그대로</b>");
        Entry titleOnly = feed.getEntries().get(1);
        assertThat(titleOnly.getContents()).isEmpty();
        assertThat(titleOnly.getSummary()).isNull();
        assertThat(titleOnly.getAlternateLinks()).isNotEmpty();
    }

    @Test
    void emptyFeedIsStillValid() throws Exception {
        SyndFeed feed = new SyndFeedInput().build(RssFeedWriterTest.reader(writer.write(FeedFixtures.feed(List.of()))));

        assertThat(feed.getEntries()).isEmpty();
        assertThat(feed.getTitle()).isNotBlank();
    }
}
