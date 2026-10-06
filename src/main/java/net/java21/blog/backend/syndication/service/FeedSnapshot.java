package net.java21.blog.backend.syndication.service;

import java.time.Instant;
import java.util.List;

/**
 * 한 블로그(또는 카테고리) 피드의 내용(형식과 무관, 002 contracts/api.md 블로그 피드 절). 주소는 모두 절대 주소.
 *
 * @param link     블로그 홈 또는 카테고리 화면
 * @param rssUrl   이 피드의 RSS 주소({@code atom:link rel="self"})
 * @param atomUrl  이 피드의 Atom 주소. 카테고리 피드는 Atom이 없어 null
 * @param author   블로그 주인 닉네임(이메일은 넣지 않는다)
 * @param updated  담은 글·블로그 설정의 가장 늦은 수정 시각
 */
public record FeedSnapshot(String title, String link, String description, String rssUrl, String atomUrl,
        String author, Instant updated, List<FeedEntry> entries) {

    public FeedSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
