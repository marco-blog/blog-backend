package net.java21.blog.backend.syndication.service;

import java.time.Instant;
import java.util.List;

/**
 * 피드 항목 하나(형식과 무관). {@code contentHtml}(FULL)과 {@code summary}(SUMMARY) 중 하나만 있거나, 본문 노출 가능이 아닌 글이면 둘 다 null.
 *
 * @param categories 카테고리 이름과 태그(본문 노출 가능 글만)
 */
public record FeedEntry(String title, String link, Instant published, Instant updated, String contentHtml,
        String summary, List<String> categories) {

    public FeedEntry {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
