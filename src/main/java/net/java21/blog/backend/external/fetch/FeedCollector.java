package net.java21.blog.backend.external.fetch;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.java21.blog.backend.common.net.FetchResult;
import net.java21.blog.backend.common.net.SafeHttpFetcher;
import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.FetchResultCode;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.external.feed.FeedParseException;
import net.java21.blog.backend.external.feed.FeedParser;
import net.java21.blog.backend.external.feed.ParsedFeed;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.thumbnail.ExternalThumbnailService;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 피드 하나 수집(007 research E5). 외부 요청은 트랜잭션 밖에서 끝내고, 결과 기록과 글 저장은 피드 하나당 한 트랜잭션이다. 쿼리는 등록 읽기
 * 1회 + (성공 기록 1회 + guid·링크 {@code IN} 2회) + 새 글·바뀐 글당 쓰기. 성공 기록은 ACTIVE일 때만 바뀌므로 그사이 중지·차단된
 * 블로그의 글은 저장하지 않는다. 썸네일은 커밋 뒤 소유 인증된 블로그의 새 글만 받는다(research E7).
 */
@Service
public class FeedCollector {

    private static final Logger log = LoggerFactory.getLogger(FeedCollector.class);

    /** 수집에 쓰는 등록 값(트랜잭션 밖으로 가져감). */
    record Snapshot(long id, String feedUrl, String etag, String lastModified, Instant lastSuccessAt,
            boolean verified, long defaultTopicId) {
    }

    /** 한 번 수집의 결과(시험·로그용). */
    public enum Outcome {
        SKIPPED, NOT_MODIFIED, OK, FAILED, STOPPED
    }

    private final ExternalBlogRepository blogRepository;
    private final SafeHttpFetcher fetcher;
    private final FeedParser parser;
    private final ExternalPostUpserter upserter;
    private final TopicAssigner assigner;
    private final FeedStopNotifier stopNotifier;
    private final ExternalThumbnailService thumbnails;
    private final SystemSettingsService settings;
    private final ExternalFeedProperties properties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final Clock clock;

    public FeedCollector(ExternalBlogRepository blogRepository, SafeHttpFetcher fetcher, FeedParser parser,
            ExternalPostUpserter upserter, TopicAssigner assigner, FeedStopNotifier stopNotifier,
            ExternalThumbnailService thumbnails, SystemSettingsService settings, ExternalFeedProperties properties,
            ApplicationEventPublisher events, TransactionTemplate tx, Clock clock) {
        this.blogRepository = blogRepository;
        this.fetcher = fetcher;
        this.parser = parser;
        this.upserter = upserter;
        this.assigner = assigner;
        this.stopNotifier = stopNotifier;
        this.thumbnails = thumbnails;
        this.settings = settings;
        this.properties = properties;
        this.events = events;
        this.tx = tx;
        this.clock = clock;
    }

    public Outcome collect(long blogId) {
        Snapshot s = tx.execute(status -> blogRepository.findById(blogId)
                .filter(b -> b.getStatus() == ExternalBlogStatus.ACTIVE)
                .map(FeedCollector::snapshot)
                .orElse(null));
        if (s == null) {
            return Outcome.SKIPPED;
        }
        URI uri;
        try {
            uri = URI.create(s.feedUrl());
        } catch (IllegalArgumentException e) {
            return failure(blogId, FetchResultCode.BLOCKED_ADDRESS, null);
        }
        FetchResult result = fetcher.fetch(SafeHttpFetcher.Request.get(uri, SafeHttpFetcher.Limit.FEED)
                .conditional(s.etag(), s.lastModified()));
        Instant now = clock.instant();
        if (!result.isSuccess()) {
            return failure(blogId, FetchResultCode.of(result.failure()), result.httpStatusOrNull());
        }
        if (result.notModified()) {
            tx.executeWithoutResult(status -> blogRepository.recordNotModified(blogId, ExternalBlogStatus.ACTIVE,
                    FetchResultCode.NOT_MODIFIED, now, next(now)));
            return Outcome.NOT_MODIFIED;
        }
        if (result.status() != 200) {
            return failure(blogId, FetchResultCode.HTTP_ERROR, result.status());
        }
        ParsedFeed feed;
        try {
            feed = parser.parse(result.body(), result.contentType(), result.finalUri(), now,
                    properties.maxItemsPerFetch());
        } catch (FeedParseException e) {
            return failure(blogId, FetchResultCode.PARSE_ERROR, result.status());
        }
        List<FeedItem> items = s.lastSuccessAt() == null ? withinInitialWindow(feed.items(), now) : feed.items();
        List<ExternalPost> created = tx.execute(status -> {
            int updated = blogRepository.recordFetched(blogId, ExternalBlogStatus.ACTIVE, FetchResultCode.OK,
                    result.status(), limit(result.etag(), 255), limit(result.lastModified(), 64),
                    limit(feed.title(), ExternalBlog.TITLE_MAX), limit(feed.siteUrl(), ExternalBlog.URL_MAX),
                    feed.format(), now, next(now));
            if (updated == 0) {
                return null;
            }
            ExternalPostUpserter.Result saved = upserter.upsert(blogId, s.defaultTopicId(), items, assigner.start(),
                    now);
            if (!saved.created().isEmpty() || saved.updated() > 0) {
                events.publishEvent(new PortalChangedEvent("external-fetch"));
            }
            return saved.created();
        });
        if (created == null) {
            return Outcome.SKIPPED;
        }
        if (s.verified()) {
            for (ExternalPost post : created) {
                if (post.getImageUrl() != null) {
                    thumbnails.fetchFor(post.getId());
                }
            }
        }
        return Outcome.OK;
    }

    private Outcome failure(long blogId, FetchResultCode code, Integer httpStatus) {
        Boolean stopped = tx.execute(status -> {
            ExternalBlog blog = blogRepository.findById(blogId).orElse(null);
            if (blog == null || blog.getStatus() != ExternalBlogStatus.ACTIVE) {
                return false;
            }
            Instant now = clock.instant();
            Duration delay = FetchBackoff.delay(settings.externalFetchInterval(), blog.getConsecutiveFailures() + 1,
                    properties.maxBackoff());
            blog.recordFailure(code, httpStatus, now, now.plus(delay));
            return stopNotifier.stopIfExpired(blog, now);
        });
        log.info("External feed {} failed: {} {}", blogId, code, httpStatus == null ? "" : httpStatus);
        return Boolean.TRUE.equals(stopped) ? Outcome.STOPPED : Outcome.FAILED;
    }

    private List<FeedItem> withinInitialWindow(List<FeedItem> items, Instant now) {
        Instant from = now.minus(properties.initialWindow());
        return items.stream().filter(i -> i.publishedAt() == null || !i.publishedAt().isBefore(from)).toList();
    }

    private Instant next(Instant now) {
        long jitter = properties.fetchJitter().toMillis();
        long extra = jitter <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitter + 1);
        return now.plus(settings.externalFetchInterval()).plusMillis(extra);
    }

    private static String limit(String value, int max) {
        return value == null || value.length() > max ? null : value;
    }

    private static Snapshot snapshot(ExternalBlog b) {
        return new Snapshot(b.getId(), b.getFeedUrl(), b.getEtag(), b.getLastModified(), b.getLastSuccessAt(),
                b.isOwnershipVerified(), b.getDefaultTopic().getId());
    }
}
