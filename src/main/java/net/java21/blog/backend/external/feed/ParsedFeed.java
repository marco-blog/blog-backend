package net.java21.blog.backend.external.feed;

import java.util.List;

/**
 * 읽은 피드(007 research E3).
 *
 * @param format  RSS 또는 ATOM
 * @param title   블로그 이름(태그 제거, 200자, 없으면 null)
 * @param siteUrl 블로그 주소(채널 link, 없으면 피드 주소의 호스트 루트)
 * @param items   피드 순서의 항목(읽을 수 없는 항목은 뺌)
 */
public record ParsedFeed(FeedFormat format, String title, String siteUrl, List<FeedItem> items) {

    public ParsedFeed {
        items = List.copyOf(items);
    }
}
