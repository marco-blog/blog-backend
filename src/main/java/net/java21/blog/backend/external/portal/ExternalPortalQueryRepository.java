package net.java21.blog.backend.external.portal;

import static net.java21.blog.backend.external.domain.QExternalPostDailyClick.externalPostDailyClick;
import static net.java21.blog.backend.external.portal.ExternalPortalExposure.eb;
import static net.java21.blog.backend.external.portal.ExternalPortalExposure.ep;
import static net.java21.blog.backend.external.portal.ExternalPortalExposure.parentTopic;
import static net.java21.blog.backend.external.portal.ExternalPortalExposure.topic;
import static net.java21.blog.backend.external.portal.ExternalPortalExposure.visible;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.external.thumbnail.ExternalThumbnailService;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.service.ExternalPortalSource.PopularityCandidate;
import net.java21.blog.backend.portal.service.PortalCursor;
import net.java21.blog.backend.portal.service.PortalItem;
import net.java21.blog.backend.portal.service.PortalKey;
import net.java21.blog.backend.portal.service.PortalSourceType;
import org.springframework.stereotype.Repository;

/**
 * 포털에 섞는 외부 글 쿼리(007 research E13, QueryDSL DTO projection). 모든 쿼리는 {@link ExternalPortalExposure} 조건을 쓰고 한 번에
 * 끝난다. 카드 썸네일은 소유 인증된 블로그 글만 {@code /media/external/{key}}다(FR-128).
 */
@Repository
public class ExternalPortalQueryRepository {

    private final JPAQueryFactory queryFactory;

    public ExternalPortalQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    private com.querydsl.jpa.impl.JPAQuery<Tuple> cardQuery() {
        return ExternalPortalExposure.join(queryFactory.select(ep.id, ep.title, ep.summary, ep.thumbnailKey,
                eb.ownershipVerified, topic.id, eb.id, eb.title, eb.siteUrl, eb.feedUrl, ep.publishedAt));
    }

    private static PortalItem toItem(Tuple t) {
        Long id = t.get(ep.id);
        Long blogId = t.get(eb.id);
        String key = t.get(ep.thumbnailKey);
        String thumbnail = key != null && Boolean.TRUE.equals(t.get(eb.ownershipVerified))
                ? ExternalThumbnailService.urlOf(key) : null;
        String host = host(t.get(eb.siteUrl), t.get(eb.feedUrl));
        String title = t.get(eb.title);
        PortalCardResponse.ExternalBlogRef ref = new PortalCardResponse.ExternalBlogRef(blogId,
                title == null || title.isBlank() ? host : title, host);
        Instant publishedAt = t.get(ep.publishedAt);
        return new PortalItem(PortalSourceType.EXTERNAL, id, blogId, publishedAt,
                PortalCardResponse.external(id, t.get(ep.title), t.get(ep.summary), thumbnail, t.get(topic.id), ref,
                        publishedAt));
    }

    /** 사이트 주소(없으면 피드 주소)의 호스트. 읽을 수 없으면 빈 문자열. */
    static String host(String siteUrl, String feedUrl) {
        for (String url : new String[] {siteUrl, feedUrl}) {
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                String host = URI.create(url.strip()).getHost();
                if (host != null && !host.isBlank()) {
                    return host;
                }
            } catch (IllegalArgumentException e) {
                // 다음 후보
            }
        }
        return "";
    }

    /** 발행 최신순(같으면 id 내림차순) 카드 {@code limit}장. {@code after} 다음부터(같은 시각이면 내부 글이 앞). */
    public List<PortalItem> findLatest(Instant now, PortalCursor.Position after, int limit) {
        return cardQuery()
                .where(visible(now), after(after))
                .orderBy(ep.publishedAt.desc(), ep.id.desc())
                .limit(limit)
                .fetch().stream().map(ExternalPortalQueryRepository::toItem).toList();
    }

    private static BooleanExpression after(PortalCursor.Position position) {
        if (position == null) {
            return null;
        }
        if (position.source() == PortalSourceType.INTERNAL) {
            // 같은 시각의 외부 글은 모두 내부 글 뒤
            return ep.publishedAt.loe(position.publishedAt());
        }
        return ep.publishedAt.lt(position.publishedAt())
                .or(ep.publishedAt.eq(position.publishedAt()).and(ep.id.lt(position.id())));
    }

    /** id 목록의 카드를 그 순서대로(노출이 아닌 글은 빠짐). */
    public List<PortalItem> findCards(List<Long> ids, Instant now) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Integer> order = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            order.putIfAbsent(ids.get(i), i);
        }
        return cardQuery()
                .where(visible(now), ep.id.in(order.keySet()))
                .fetch().stream().map(ExternalPortalQueryRepository::toItem)
                .sorted(Comparator.comparingInt(item -> order.get(item.id())))
                .toList();
    }

    /** 주제의 가벼운 행(id·발행 시각). */
    public List<PortalKey> findTopicKeys(Instant now, Collection<Long> topicIds, int limit) {
        if (topicIds.isEmpty() || limit <= 0) {
            return List.of();
        }
        return ExternalPortalExposure.join(queryFactory.select(ep.id, ep.publishedAt))
                .where(visible(now), topic.id.in(topicIds))
                .orderBy(ep.publishedAt.desc(), ep.id.desc())
                .limit(limit)
                .fetch().stream()
                .map(t -> new PortalKey(PortalSourceType.EXTERNAL, t.get(ep.id), t.get(ep.publishedAt)))
                .toList();
    }

    public long countTopic(Instant now, Collection<Long> topicIds) {
        if (topicIds.isEmpty()) {
            return 0;
        }
        Long count = ExternalPortalExposure.join(queryFactory.select(ep.count()))
                .where(visible(now), topic.id.in(topicIds))
                .fetchOne();
        return count == null ? 0 : count;
    }

    /** {@code fromDay}부터의 일별 클릭 합이 있는 노출 글(쿼리 1회). */
    public List<PopularityCandidate> findPopularityCandidates(Instant now, LocalDate fromDay) {
        NumberExpression<Long> clicks = externalPostDailyClick.clicks.sumLong();
        return queryFactory.select(ep.id, eb.id, topic.id, ep.publishedAt, clicks)
                .from(externalPostDailyClick)
                .join(externalPostDailyClick.externalPost, ep)
                .join(ep.externalBlog, eb)
                .join(ep.topic, topic)
                .leftJoin(topic.parent, parentTopic)
                .where(externalPostDailyClick.id.clickDate.goe(fromDay), visible(now))
                .groupBy(ep.id, eb.id, topic.id, ep.publishedAt)
                .fetch().stream()
                .map(t -> new PopularityCandidate(t.get(ep.id), t.get(eb.id), t.get(topic.id), t.get(ep.publishedAt),
                        Optional.ofNullable(t.get(clicks)).map(Number::longValue).orElse(0L)))
                .toList();
    }

    /** 주제(소분류) id → {@code since} 이후 발행 노출 글 수(쿼리 1회). */
    public Map<Long, Long> countRecentByTopic(Instant now, Instant since) {
        Map<Long, Long> counts = new HashMap<>();
        for (Tuple row : ExternalPortalExposure.join(queryFactory.select(topic.id, ep.count()))
                .where(visible(now), ep.publishedAt.goe(since))
                .groupBy(topic.id)
                .fetch()) {
            counts.put(row.get(topic.id), row.get(ep.count()));
        }
        return counts;
    }

    /** 원문 이동(research E14): 노출 조건을 만족하는 글의 링크(쿼리 1회). */
    public Optional<String> findVisibleLink(long id, Instant now) {
        return Optional.ofNullable(ExternalPortalExposure.join(queryFactory.select(ep.link))
                .where(ep.id.eq(id), visible(now))
                .fetchFirst());
    }
}
