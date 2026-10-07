package net.java21.blog.backend.portal.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 인기 점수 스냅숏(003 research P4): 점수 &gt; 0인 포털 노출 글, 점수 내림차순(같으면 발행 최신순, id 내림차순). 007부터 내부·외부
 * 글이 한 목록에 섞인다(같은 점수 체계, FR-124). 포털 캐시의 한
 * 항목({@code POPULARITY})으로 5분마다 다시 만든다. 점수는 저장하지 않는다.
 */
public record PopularitySnapshot(List<Entry> entries) {

    public PopularitySnapshot {
        entries = List.copyOf(entries);
    }

    /** {@code postId}·{@code blogId}는 출처 안의 id다(007: 외부 글은 {@code external_posts.id}·{@code external_blogs.id}). */
    public record Entry(Long postId, Long blogId, Long topicId, Instant publishedAt, double score,
            PortalSourceType source) {

        public Entry {
            source = source == null ? PortalSourceType.INTERNAL : source;
        }

        /** 내부 글(003). */
        public Entry(Long postId, Long blogId, Long topicId, Instant publishedAt, double score) {
            this(postId, blogId, topicId, publishedAt, score, PortalSourceType.INTERNAL);
        }

        /** 블로그당 2편 제한 키({@link PortalItem#capKey()}와 같은 형식). */
        public String capKey() {
            return source.code() + ":" + blogId;
        }
    }

    /** 주제 id 집합에 속한 글만(순서 유지, 주제 페이지 인기순). */
    public List<Entry> forTopics(Collection<Long> topicIds) {
        return forTopics(topicIds, PortalSourceFilter.ALL);
    }

    /** 주제 id 집합에 속하고 출처 필터에 맞는 글만(순서 유지, 007 주제 페이지 {@code source}). */
    public List<Entry> forTopics(Collection<Long> topicIds, PortalSourceFilter filter) {
        return entries.stream()
                .filter(entry -> entry.topicId() != null && topicIds.contains(entry.topicId())
                        && filter.includes(entry.source()))
                .toList();
    }
}
