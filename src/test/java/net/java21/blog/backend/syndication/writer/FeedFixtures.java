package net.java21.blog.backend.syndication.writer;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.syndication.service.FeedEntry;
import net.java21.blog.backend.syndication.service.FeedSnapshot;

/** 작성기 테스트용 피드. */
final class FeedFixtures {

    static final Instant PUBLISHED = Instant.parse("2026-10-06T04:24:19Z");
    static final Instant UPDATED = Instant.parse("2026-10-07T01:02:03Z");

    private FeedFixtures() {
    }

    static FeedSnapshot feed(List<FeedEntry> entries) {
        return new FeedSnapshot("마르코 & <블로그> 😀", "https://blog.java21.net/marco", "자바 & 스프링",
                "https://blog.java21.net/marco/rss", "https://blog.java21.net/marco/atom", "마르코", UPDATED, entries);
    }

    static FeedEntry full(long id) {
        return new FeedEntry("글 " + id + " <&> 🎉", "https://blog.java21.net/marco/" + id, PUBLISHED, UPDATED,
                "<p>본문 <img src=\"https://blog.java21.net/media/k\"></p>", null, List.of("Spring", "java"));
    }

    static FeedEntry summary(long id) {
        return new FeedEntry("요약 글 " + id, "https://blog.java21.net/marco/" + id, PUBLISHED, UPDATED, null,
                "요약 <b>그대로</b>", List.of());
    }

    static FeedEntry titleOnly(long id) {
        return new FeedEntry("보호 글 " + id, "https://blog.java21.net/marco/" + id, PUBLISHED, UPDATED, null, null,
                List.of());
    }
}
