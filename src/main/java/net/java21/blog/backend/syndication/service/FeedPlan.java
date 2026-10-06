package net.java21.blog.backend.syndication.service;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.syndication.repository.FeedVersionRow;

/**
 * 304 판단 결과: 담을 글의 버전과 그로 만든 약한 {@code ETag}·{@code Last-Modified}. 바뀌었을 때만 {@link FeedService#snapshot}으로 본문을 읽는다.
 */
public record FeedPlan(FeedSource source, List<FeedVersionRow> versions, String etag, Instant lastModified) {

    public FeedPlan {
        versions = versions == null ? List.of() : List.copyOf(versions);
    }
}
