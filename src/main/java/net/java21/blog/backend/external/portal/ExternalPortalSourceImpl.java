package net.java21.blog.backend.external.portal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.portal.service.ExternalPortalSource;
import net.java21.blog.backend.portal.service.PortalCursor;
import net.java21.blog.backend.portal.service.PortalItem;
import net.java21.blog.backend.portal.service.PortalKey;
import org.springframework.stereotype.Component;

/** 003 포털에 외부 글을 붙이는 구현(007 research E13). 쿼리는 {@link ExternalPortalQueryRepository}. */
@Component
public class ExternalPortalSourceImpl implements ExternalPortalSource {

    private final ExternalPortalQueryRepository queries;

    public ExternalPortalSourceImpl(ExternalPortalQueryRepository queries) {
        this.queries = queries;
    }

    @Override
    public List<PortalItem> latest(Instant now, PortalCursor.Position after, int limit) {
        return queries.findLatest(now, after, limit);
    }

    @Override
    public List<PortalKey> topicKeys(Instant now, Collection<Long> topicIds, int limit) {
        return queries.findTopicKeys(now, topicIds, limit);
    }

    @Override
    public long topicCount(Instant now, Collection<Long> topicIds) {
        return queries.countTopic(now, topicIds);
    }

    @Override
    public List<PortalItem> cards(List<Long> ids, Instant now) {
        return queries.findCards(ids, now);
    }

    @Override
    public List<PopularityCandidate> popularityCandidates(Instant now, LocalDate fromDay) {
        return queries.findPopularityCandidates(now, fromDay);
    }

    @Override
    public Map<Long, Long> recentCountsByTopic(Instant now, Instant since) {
        return queries.countRecentByTopic(now, since);
    }
}
