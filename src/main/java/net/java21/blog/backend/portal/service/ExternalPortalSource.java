package net.java21.blog.backend.portal.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 포털에 섞을 외부 블로그 글(007 FR-123·124, research E13). 003 쿼리는 그대로 두고 서비스 계층이 두 출처를 합친다. 구현은
 * {@code external/portal/ExternalPortalSourceImpl}이고, 구현 빈이 없으면 {@link #NONE}(빈 결과)이라 003 결과와 같다.
 * 모든 메서드는 외부 글의 포털 노출 조건({@code ExternalPortalExposure})을 만족하는 글만 다룬다.
 */
public interface ExternalPortalSource {

    /** 외부 글이 없는 구현(003 그대로). */
    ExternalPortalSource NONE = new ExternalPortalSource() {
        @Override
        public boolean enabled() {
            return false;
        }

        @Override
        public List<PortalItem> latest(Instant now, PortalCursor.Position after, int limit) {
            return List.of();
        }

        @Override
        public List<PortalKey> topicKeys(Instant now, Collection<Long> topicIds, int limit) {
            return List.of();
        }

        @Override
        public long topicCount(Instant now, Collection<Long> topicIds) {
            return 0;
        }

        @Override
        public List<PortalItem> cards(List<Long> ids, Instant now) {
            return List.of();
        }

        @Override
        public List<PopularityCandidate> popularityCandidates(Instant now, LocalDate fromDay) {
            return List.of();
        }

        @Override
        public Map<Long, Long> recentCountsByTopic(Instant now, Instant since) {
            return Map.of();
        }
    };

    /** 인기 점수 후보: 최근 클릭 합이 있는 노출 글. */
    record PopularityCandidate(Long id, Long blogId, Long topicId, Instant publishedAt, long clicks) {
    }

    default boolean enabled() {
        return true;
    }

    /** 최신 카드(발행 시각 내림, id 내림) {@code limit}장. {@code after}가 있으면 (시각, 출처, id) 순서에서 그 뒤. */
    List<PortalItem> latest(Instant now, PortalCursor.Position after, int limit);

    /** 주제(소분류 id들)의 가벼운 행 {@code limit}개(발행 시각 내림, id 내림). */
    List<PortalKey> topicKeys(Instant now, Collection<Long> topicIds, int limit);

    /** 주제(소분류 id들)의 노출 글 수. */
    long topicCount(Instant now, Collection<Long> topicIds);

    /** id 목록의 카드를 그 순서대로(노출이 아닌 글은 빠짐). 쿼리 1회. */
    List<PortalItem> cards(List<Long> ids, Instant now);

    /** {@code fromDay}(UTC 날짜, 포함)부터의 일별 클릭 합이 있는 노출 글. */
    List<PopularityCandidate> popularityCandidates(Instant now, LocalDate fromDay);

    /** 주제(소분류) id → {@code since} 이후 발행 노출 글 수(쿼리 1회). */
    Map<Long, Long> recentCountsByTopic(Instant now, Instant since);
}
