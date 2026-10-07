package net.java21.blog.backend.external.job;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.java21.blog.backend.common.net.FetchFailure;
import net.java21.blog.backend.common.net.FetchResult;
import net.java21.blog.backend.common.net.SafeHttpFetcher;
import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository.LinkTarget;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 원문 링크 점검(007 FR-117, research E12). {@code blog.external.link-check-cron}(기본 매주 월요일 04:30)에 ACTIVE 글 중 7일 안에
 * 점검하지 않은 글을 오래된 순으로 최대 {@code link-check-batch}(500)개 골라(스케줄러 스레드) 호스트별로 묶어 수집 풀
 * ({@code feedFetchExecutor})에 맡긴다. 같은 호스트에는 1초에 1번 이하로 요청한다.
 * <ul>
 *   <li>{@code HEAD}, 405·501이면 {@code GET} + {@code Range: bytes=0-0}(본문 읽지 않음)</li>
 *   <li>404·410, 또는 호스트 이름이 없음(DNS)이면 REMOVED({@code LINK_BROKEN})</li>
 *   <li>그 밖의 실패(시간 초과, 5xx, 403, 내부망으로 리다이렉트)는 내리지 않고 다음 주에 다시</li>
 *   <li>결과와 관계없이 {@code link_checked_at = now}. 내린 글이 있으면 커밋 후 포털 캐시 무효화</li>
 * </ul>
 */
@Component
public class LinkCheckJob {

    /** 이 기간 안에 점검한 글은 건너뛴다. */
    static final Duration RECHECK_AFTER = Duration.ofDays(7);
    /** 같은 호스트 요청 간격. */
    static final Duration HOST_INTERVAL = Duration.ofSeconds(1);
    /** 큐가 찼을 때 다시 맡기기까지 기다리는 시간과 횟수. */
    static final Duration RESUBMIT_WAIT = Duration.ofSeconds(1);
    static final int RESUBMIT_TRIES = 120;

    private static final Logger log = LoggerFactory.getLogger(LinkCheckJob.class);

    /** 기다리기(시험은 기록만 한다). */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** 한 번 돌린 결과. */
    public record Summary(int selected, int removed) {
    }

    private final ExternalPostRepository postRepository;
    private final SafeHttpFetcher fetcher;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final ExternalFeedProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;

    @Autowired
    public LinkCheckJob(ExternalPostRepository postRepository, SafeHttpFetcher fetcher,
            @Qualifier("feedFetchExecutor") TaskExecutor executor, TransactionTemplate tx,
            ApplicationEventPublisher events, ExternalFeedProperties properties, Clock clock) {
        this(postRepository, fetcher, executor, tx, events, properties, clock,
                duration -> Thread.sleep(duration.toMillis()));
    }

    public LinkCheckJob(ExternalPostRepository postRepository, SafeHttpFetcher fetcher, TaskExecutor executor,
            TransactionTemplate tx, ApplicationEventPublisher events, ExternalFeedProperties properties, Clock clock,
            Sleeper sleeper) {
        this.postRepository = postRepository;
        this.fetcher = fetcher;
        this.executor = executor;
        this.tx = tx;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    @Scheduled(cron = "${blog.external.link-check-cron:0 30 4 * * MON}")
    public void run() {
        Summary summary = runOnce();
        log.info("External link check: selected={}, removed={}", summary.selected(), summary.removed());
    }

    /**
     * 대상을 골라 호스트별 작업으로 맡긴다. 맡긴 작업이 끝날 때까지 기다리지 않으므로 {@code removed}는 동기 실행기(시험)에서만 정확하다.
     */
    public Summary runOnce() {
        Instant now = clock.instant();
        List<LinkTarget> targets = postRepository.findLinkCheckTargets(ExternalPostStatus.ACTIVE,
                now.minus(RECHECK_AFTER), org.springframework.data.domain.Limit.of(properties.linkCheckBatch()));
        Map<String, List<LinkTarget>> byHost = new LinkedHashMap<>();
        for (LinkTarget target : targets) {
            byHost.computeIfAbsent(host(target.link()), h -> new ArrayList<>()).add(target);
        }
        AtomicInteger removed = new AtomicInteger();
        for (List<LinkTarget> group : byHost.values()) {
            if (!submit(() -> checkHost(group, removed))) {
                log.warn("Link check queue stayed full; remaining hosts wait for the next run");
                break;
            }
        }
        return new Summary(targets.size(), removed.get());
    }

    private boolean submit(Runnable task) {
        for (int i = 0; i < RESUBMIT_TRIES; i++) {
            try {
                executor.execute(task);
                return true;
            } catch (TaskRejectedException e) {
                if (!pause(RESUBMIT_WAIT)) {
                    return false;
                }
            }
        }
        return false;
    }

    private boolean pause(Duration duration) {
        try {
            sleeper.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 한 호스트의 링크를 차례로(1초 간격) 점검한다. */
    void checkHost(List<LinkTarget> group, AtomicInteger removed) {
        boolean first = true;
        for (LinkTarget target : group) {
            if (!first && !pause(HOST_INTERVAL)) {
                return;
            }
            first = false;
            try {
                if (record(target.id(), isBroken(target.link()))) {
                    removed.incrementAndGet();
                }
            } catch (RuntimeException e) {
                log.warn("Link check of external post {} failed", target.id(), e);
            }
        }
    }

    /** 확실히 없어진 경우(404·410·이름 없음)만 true. */
    boolean isBroken(String link) {
        URI uri;
        try {
            uri = URI.create(link);
        } catch (IllegalArgumentException e) {
            return false;
        }
        FetchResult result = fetcher.fetch(SafeHttpFetcher.Request.head(uri));
        Integer status = result.httpStatusOrNull();
        if (result.failure() == FetchFailure.HTTP_ERROR && status != null && (status == 405 || status == 501)) {
            result = fetcher.fetch(SafeHttpFetcher.Request.get(uri, SafeHttpFetcher.Limit.NONE)
                    .withRange("bytes=0-0"));
            status = result.httpStatusOrNull();
        }
        if (result.failure() == FetchFailure.DNS_ERROR) {
            return true;
        }
        return result.failure() == FetchFailure.HTTP_ERROR && status != null && (status == 404 || status == 410);
    }

    /** 점검 시각을 남기고 없어졌으면 내린다. 내렸으면 커밋 후 포털 캐시 무효화. @return 이번에 내렸으면 true */
    private boolean record(long postId, boolean broken) {
        Boolean changed = tx.execute(status -> postRepository.findById(postId).map(post -> {
            post.linkChecked(clock.instant());
            boolean down = broken && post.remove(RemovedReason.LINK_BROKEN);
            if (down) {
                events.publishEvent(new PortalChangedEvent("external-link-check"));
            }
            return down;
        }).orElse(false));
        return Boolean.TRUE.equals(changed);
    }

    static String host(String link) {
        try {
            String host = URI.create(link).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
