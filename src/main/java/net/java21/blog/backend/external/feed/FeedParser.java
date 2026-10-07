package net.java21.blog.backend.external.feed;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import com.rometools.rome.feed.atom.Entry;
import com.rometools.rome.feed.rss.Item;
import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndLink;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.springframework.stereotype.Component;

/**
 * RSS 0.9x/1.0/2.0·Atom 읽기(007 research E3·E4). ROME {@link SyndFeedInput}에 {@code allowDoctypes=false}를 두어 DOCTYPE이 있는
 * 문서(XXE·엔터티 폭탄)는 읽지 않고, {@link XmlReader}로 문서 선언·Content-Type의 문자 집합을 판별한다. 결과에는 본문이 없다(SC-021).
 */
@Component
public class FeedParser {

    static final int MAX_LINK = 2000;
    static final int MAX_CHANNEL_TITLE = 200;
    static final int MAX_CATEGORIES = 20;
    static final int MAX_CATEGORY = 100;
    /** 인증 코드를 찾는 항목 수(research E8). */
    static final int VERIFY_ITEMS = 20;

    /**
     * @param body        응답 본문
     * @param contentType 응답 {@code Content-Type}(null 가능)
     * @param feedUri     피드 최종 주소(상대 주소의 기준)
     * @param now         수집 시각(미래 발행 시각을 당김)
     * @param maxItems    읽을 항목 수 상한
     */
    public ParsedFeed parse(byte[] body, String contentType, URI feedUri, Instant now, int maxItems)
            throws FeedParseException {
        SyndFeed feed = read(body, contentType);
        FeedFormat format = feed.getFeedType() != null && feed.getFeedType().toLowerCase(Locale.ROOT).startsWith("atom")
                ? FeedFormat.ATOM : FeedFormat.RSS;
        String title = SummaryExtractor.truncate(HtmlScanner.text(feed.getTitle()), MAX_CHANNEL_TITLE);
        String siteUrl = ItemImagePicker.accept(siteLink(feed), feedUri);
        if (siteUrl == null && feedUri != null) {
            siteUrl = feedUri.getScheme() + "://" + feedUri.getRawAuthority() + "/";
        }
        List<FeedItem> items = new ArrayList<>();
        for (SyndEntry entry : feed.getEntries()) {
            if (items.size() >= maxItems) {
                break;
            }
            FeedItem item = item(entry, feedUri, now);
            if (item != null) {
                items.add(item);
            }
        }
        return new ParsedFeed(format, title == null || title.isEmpty() ? null : title, siteUrl, items);
    }

    /**
     * 채널 제목·설명과 앞쪽 항목 20개의 제목·요약·본문 텍스트 어디에 {@code needle}이 있는지(소유 인증, research E8). 텍스트는 이 안에서만 쓰고
     * 남기지 않는다.
     */
    public boolean containsText(byte[] body, String contentType, String needle) throws FeedParseException {
        SyndFeed feed = read(body, contentType);
        if (contains(feed.getTitle(), needle) || contains(feed.getDescription(), needle)) {
            return true;
        }
        int n = 0;
        for (SyndEntry entry : feed.getEntries()) {
            if (n++ >= VERIFY_ITEMS) {
                break;
            }
            if (contains(entry.getTitle(), needle)
                    || (entry.getDescription() != null && contains(entry.getDescription().getValue(), needle))) {
                return true;
            }
            for (SyndContent content : entry.getContents()) {
                if (contains(content.getValue(), needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(String html, String needle) {
        return html != null && html.contains(needle) && HtmlScanner.text(html).contains(needle);
    }

    private static SyndFeed read(byte[] body, String contentType) throws FeedParseException {
        if (body == null || body.length == 0) {
            throw new FeedParseException("Empty body", null);
        }
        SyndFeedInput input = new SyndFeedInput();
        input.setAllowDoctypes(false);
        input.setPreserveWireFeed(true);
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(body), contentType, true)) {
            return input.build(reader);
        } catch (FeedException | IOException | IllegalArgumentException | IllegalStateException e) {
            throw new FeedParseException("Not a feed", e);
        }
    }

    private static FeedItem item(SyndEntry entry, URI feedUri, Instant now) {
        String guid = guidOf(entry);
        String link = linkOf(entry, guid, feedUri);
        if (link == null) {
            return null;
        }
        URI linkUri = URI.create(link);
        String html = summarySource(entry);
        HtmlScanner.Scan scan = HtmlScanner.scan(html, linkUri);
        String summary = scan.text().isEmpty() ? null : SummaryExtractor.ellipsize(scan.text(), SummaryExtractor.SUMMARY_MAX);
        // 요약 출처(description)에 이미지가 없으면 본문(content)의 첫 이미지를 본다. 본문 문자열은 여기서 버린다.
        List<HtmlScanner.Image> images = new ArrayList<>(scan.images());
        if (images.isEmpty()) {
            for (SyndContent content : entry.getContents()) {
                images.addAll(HtmlScanner.scan(content.getValue(), linkUri).images());
            }
        }
        String imageUrl = ItemImagePicker.pick(entry, images, linkUri);
        String title = SummaryExtractor.truncate(HtmlScanner.text(entry.getTitle()), SummaryExtractor.TITLE_MAX);
        if (title == null || title.isEmpty()) {
            title = lastSegment(linkUri);
        }
        return new FeedItem(guid, link, title, summary, imageUrl, publishedAt(entry, now), categories(entry));
    }

    /** 요약 계산에 쓰는 HTML: description(Atom summary), 없으면 content. */
    private static String summarySource(SyndEntry entry) {
        if (entry.getDescription() != null && entry.getDescription().getValue() != null
                && !entry.getDescription().getValue().isBlank()) {
            return entry.getDescription().getValue();
        }
        StringBuilder sb = new StringBuilder();
        for (SyndContent content : entry.getContents()) {
            if (content.getValue() != null) {
                sb.append(content.getValue()).append(' ');
            }
        }
        return sb.toString();
    }

    private static String guidOf(SyndEntry entry) {
        Object wire = entry.getWireEntry();
        String guid = null;
        if (wire instanceof Item item) {
            guid = item.getGuid() == null ? null : item.getGuid().getValue();
        } else if (wire instanceof Entry atom) {
            guid = atom.getId();
        } else {
            guid = entry.getUri();
        }
        if (guid == null || guid.isBlank()) {
            return null;
        }
        guid = guid.strip();
        return guid.length() > 1000 ? guid.substring(0, 1000) : guid;
    }

    private static String linkOf(SyndEntry entry, String guid, URI feedUri) {
        String raw = entry.getLink();
        if ((raw == null || raw.isBlank()) && guid != null && guid.matches("(?i)^https?://.*")) {
            raw = guid;
        }
        URI resolved = HtmlScanner.resolve(feedUri, raw);
        if (resolved == null || resolved.getScheme() == null || resolved.getHost() == null) {
            return null;
        }
        String scheme = resolved.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return null;
        }
        String s = resolved.toString();
        return s.length() > MAX_LINK ? null : s;
    }

    private static Instant publishedAt(SyndEntry entry, Instant now) {
        Date date = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        if (date == null) {
            return null;
        }
        Instant at = date.toInstant();
        return at.isAfter(now) ? now : at;
    }

    private static List<String> categories(SyndEntry entry) {
        List<String> out = new ArrayList<>();
        for (SyndCategory category : entry.getCategories()) {
            if (out.size() >= MAX_CATEGORIES) {
                break;
            }
            String name = HtmlScanner.collapse(category.getName());
            if (!name.isEmpty()) {
                out.add(SummaryExtractor.truncate(name, MAX_CATEGORY));
            }
        }
        return out;
    }

    /** 채널 link. Atom은 {@code rel=alternate}(또는 rel 없음)를 먼저. */
    private static String siteLink(SyndFeed feed) {
        if (feed.getLinks() != null) {
            for (SyndLink link : feed.getLinks()) {
                if (link.getHref() != null && (link.getRel() == null || "alternate".equalsIgnoreCase(link.getRel()))) {
                    return link.getHref();
                }
            }
        }
        return feed.getLink();
    }

    private static String lastSegment(URI link) {
        String path = link.getPath() == null ? "" : link.getPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        String segment = slash >= 0 ? path.substring(slash + 1) : path;
        if (segment.isEmpty()) {
            segment = link.getHost();
        }
        return SummaryExtractor.truncate(segment, SummaryExtractor.TITLE_MAX);
    }
}
