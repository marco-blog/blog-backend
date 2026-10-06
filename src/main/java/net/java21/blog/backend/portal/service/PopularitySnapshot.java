package net.java21.blog.backend.portal.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 인기 점수 스냅숏(003 research P4): 점수 &gt; 0인 포털 노출 글, 점수 내림차순(같으면 발행 최신순, id 내림차순). 포털 캐시의 한
 * 항목({@code POPULARITY})으로 5분마다 다시 만든다. 점수는 저장하지 않는다.
 */
public record PopularitySnapshot(List<Entry> entries) {

    public PopularitySnapshot {
        entries = List.copyOf(entries);
    }

    public record Entry(Long postId, Long blogId, Long topicId, Instant publishedAt, double score) {
    }

    /** 주제 id 집합에 속한 글만(순서 유지, 주제 페이지 인기순). */
    public List<Entry> forTopics(Collection<Long> topicIds) {
        return entries.stream().filter(entry -> entry.topicId() != null && topicIds.contains(entry.topicId()))
                .toList();
    }
}
