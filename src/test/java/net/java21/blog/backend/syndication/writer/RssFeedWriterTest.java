package net.java21.blog.backend.syndication.writer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import com.rometools.rome.feed.module.DCModule;
import com.rometools.rome.feed.rss.Channel;
import com.rometools.rome.feed.rss.Item;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.WireFeedInput;

import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;

import net.java21.blog.backend.syndication.service.FeedSnapshot;
import org.junit.jupiter.api.Test;

/**
 * RSS 2.0 작성(T085, SC-008): ROME으로 다시 읽어 필수 요소({@code title}·{@code link}·{@code description}·{@code atom:link rel=self}·
 * {@code lastBuildDate}), 항목 {@code guid}(고유 주소, isPermaLink 기본 true)·{@code pubDate} RFC 822·{@code dc:creator}·{@code category},
 * 이메일 없음, 제목의 {@code &}·{@code <}·이모지 이스케이프, 빈 피드도 유효.
 */
class RssFeedWriterTest {

    private static final Namespace ATOM = Namespace.getNamespace("http://www.w3.org/2005/Atom");
    private static final Namespace DC = Namespace.getNamespace("http://purl.org/dc/elements/1.1/");

    private final RssFeedWriter writer = new RssFeedWriter();

    @Test
    void channelHasRequiredElementsAndSelfLink() throws Exception {
        byte[] xml = writer.write(FeedFixtures.feed(List.of(FeedFixtures.full(2), FeedFixtures.full(1))));

        Channel channel = (Channel) new WireFeedInput().build(reader(xml));
        assertThat(channel.getFeedType()).isEqualTo("rss_2.0");
        assertThat(channel.getTitle()).isEqualTo("마르코 & <블로그> 😀");
        assertThat(channel.getLink()).isEqualTo("https://blog.java21.net/marco");
        assertThat(channel.getDescription()).isEqualTo("자바 & 스프링");
        assertThat(channel.getLastBuildDate()).isEqualTo(Date.from(FeedFixtures.UPDATED));

        Element root = dom(xml).getRootElement();
        assertThat(root.getAttributeValue("version")).isEqualTo("2.0");
        Element self = root.getChild("channel").getChild("link", ATOM);
        assertThat(self.getAttributeValue("rel")).isEqualTo("self");
        assertThat(self.getAttributeValue("href")).isEqualTo("https://blog.java21.net/marco/rss");
        assertThat(self.getAttributeValue("type")).isEqualTo("application/rss+xml");
        assertThat(new String(xml, StandardCharsets.UTF_8)).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                .doesNotContain("@example.com").doesNotContain("<author>")
                .contains("&amp;").contains("&lt;블로그&gt;");
    }

    @Test
    void itemsHaveGuidPubDateCreatorCategoriesAndBody() throws Exception {
        byte[] xml = writer.write(FeedFixtures.feed(List.of(FeedFixtures.full(2), FeedFixtures.full(1))));

        Channel channel = (Channel) new WireFeedInput().build(reader(xml));
        List<Item> items = channel.getItems();
        assertThat(items).hasSize(2);
        Item item = items.getFirst();
        assertThat(item.getTitle()).isEqualTo("글 2 <&> 🎉");
        assertThat(item.getLink()).isEqualTo("https://blog.java21.net/marco/2");
        assertThat(item.getGuid().getValue()).isEqualTo("https://blog.java21.net/marco/2");
        assertThat(item.getGuid().isPermaLink()).isTrue();
        assertThat(item.getPubDate()).isEqualTo(Date.from(FeedFixtures.PUBLISHED));
        assertThat(item.getDescription().getValue()).isEqualTo("<p>본문 <img src=\"https://blog.java21.net/media/k\"></p>");
        assertThat(item.getCategories()).extracting(c -> c.getValue()).containsExactly("Spring", "java");
        assertThat(((DCModule) item.getModule(DCModule.URI)).getCreator()).isEqualTo("마르코");
        assertThat(items).extracting(i -> i.getGuid().getValue()).doesNotHaveDuplicates();

        Element rawItem = dom(xml).getRootElement().getChild("channel").getChild("item");
        assertThat(rawItem.getChildText("pubDate")).isEqualTo("Tue, 06 Oct 2026 04:24:19 GMT");
        assertThat(rawItem.getChildText("creator", DC)).isEqualTo("마르코");
        String permaLink = rawItem.getChild("guid").getAttributeValue("isPermaLink");
        assertThat(permaLink == null || permaLink.equals("true")).isTrue();
    }

    @Test
    void summaryAndTitleOnlyItems() throws Exception {
        byte[] xml = writer.write(FeedFixtures.feed(List.of(FeedFixtures.summary(2), FeedFixtures.titleOnly(1))));

        List<Item> items = ((Channel) new WireFeedInput().build(reader(xml))).getItems();
        assertThat(items.getFirst().getDescription().getValue()).isEqualTo("요약 <b>그대로</b>");
        assertThat(items.get(1).getDescription()).isNull();
        assertThat(items.get(1).getTitle()).isEqualTo("보호 글 1");
        assertThat(items.get(1).getLink()).isEqualTo("https://blog.java21.net/marco/1");
        assertThat(items.get(1).getPubDate()).isNotNull();
    }

    @Test
    void emptyFeedIsStillValid() throws Exception {
        byte[] xml = writer.write(FeedFixtures.feed(List.of()));

        SyndFeed feed = new SyndFeedInput().build(reader(xml));
        assertThat(feed.getEntries()).isEmpty();
        assertThat(feed.getTitle()).isNotBlank();
        assertThat(feed.getDescription()).isNotBlank();
    }

    @Test
    void syndicationRoundTripKeepsEntries() throws Exception {
        byte[] xml = writer.write(FeedFixtures.feed(List.of(FeedFixtures.full(2))));

        SyndEntry entry = new SyndFeedInput().build(reader(xml)).getEntries().getFirst();
        assertThat(entry.getUri()).isEqualTo("https://blog.java21.net/marco/2");
        assertThat(entry.getAuthor()).isEqualTo("마르코");
    }

    @Test
    void invalidXmlCharactersAreDropped() throws Exception {
        FeedSnapshot feed = new FeedSnapshot("제목\u0001\u0008끝", "https://blog.java21.net/marco", "설명￾",
                "https://blog.java21.net/marco/rss", null, "마르코\u0000", FeedFixtures.UPDATED, List.of());

        Channel channel = (Channel) new WireFeedInput().build(reader(writer.write(feed)));

        assertThat(channel.getTitle()).isEqualTo("제목끝");
        assertThat(channel.getDescription()).isEqualTo("설명");
    }

    static InputStreamReader reader(byte[] xml) {
        return new InputStreamReader(new ByteArrayInputStream(xml), StandardCharsets.UTF_8);
    }

    static Document dom(byte[] xml) throws Exception {
        return new SAXBuilder().build(new ByteArrayInputStream(xml));
    }
}
