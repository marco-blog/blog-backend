package net.java21.blog.backend.external.fetch;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.repository.ExternalBlogQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수집할 차례인 피드를 골라 수집 풀에 넘긴다(007 research E1). 고른 행은 넘기기 전에 {@code next_fetch_at = now + lease-time}으로
 * 미뤄(임대) 수집이 끝나기 전에 다시 고르지 않는다. 풀의 큐가 차면 남은 행은 임대를 그대로 두고 다음에 다시 고른다(결정 표 15번).
 */
@Component
public class FeedFetchScheduler {

    private static final Logger log = LoggerFactory.getLogger(FeedFetchScheduler.class);

    private final ExternalBlogQueryRepository queryRepository;
    private final FeedCollector collector;
    private final TaskExecutor executor;
    private final ExternalFeedProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public FeedFetchScheduler(ExternalBlogQueryRepository queryRepository, FeedCollector collector,
            @Qualifier("feedFetchExecutor") TaskExecutor executor, ExternalFeedProperties properties,
            TransactionTemplate tx, Clock clock) {
        this.queryRepository = queryRepository;
        this.collector = collector;
        this.executor = executor;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${blog.external.poll-interval:1m}",
            initialDelayString = "${blog.external.poll-interval:1m}")
    public void run() {
        poll();
    }

    /** 고르고 넘긴 수. */
    public int poll() {
        Instant now = clock.instant();
        List<Long> ids = tx.execute(status -> {
            List<Long> due = queryRepository.findDueIds(now, properties.batchSize());
            queryRepository.lease(due, now.plus(properties.leaseTime()));
            return due;
        });
        int submitted = 0;
        for (Long id : ids == null ? List.<Long>of() : ids) {
            try {
                executor.execute(() -> collectQuietly(id));
                submitted++;
            } catch (TaskRejectedException e) {
                log.warn("Feed fetch queue is full; {} feeds wait for the next round", ids.size() - submitted);
                break;
            }
        }
        return submitted;
    }

    private void collectQuietly(long id) {
        try {
            collector.collect(id);
        } catch (RuntimeException e) {
            log.warn("External feed {} collection failed", id, e);
        }
    }
}
