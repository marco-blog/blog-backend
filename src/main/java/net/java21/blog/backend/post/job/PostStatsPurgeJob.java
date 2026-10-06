package net.java21.blog.backend.post.job;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.repository.PostDailyStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 오래된 글 일별 통계 정리(003 research P4, 결정 표 17번). {@code blog.jobs.post-stats-purge-cron}(기본 매일 04:45)마다
 * {@code stat_date}가 오늘(UTC) − {@code blog.posts.stats-retention}(90일)보다 이전인 행을 {@code blog.jobs.purge-batch-size}건씩
 * 트랜잭션을 나눠 지우고 건수를 로그에 남긴다. 인기 점수는 최근 7일만 쓴다.
 */
@Component
public class PostStatsPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(PostStatsPurgeJob.class);

    private final PostDailyStatsRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final PostsProperties posts;
    private final JobsProperties jobs;
    private final Clock clock;

    public PostStatsPurgeJob(PostDailyStatsRepository repository, TransactionTemplate transactionTemplate,
            PostsProperties posts, JobsProperties jobs, Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.posts = posts;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Scheduled(cron = "${blog.jobs.post-stats-purge-cron:" + JobsProperties.DEFAULT_POST_STATS_PURGE_CRON + "}")
    public void run() {
        purge();
    }

    /** @return 지운 행 수 */
    public long purge() {
        LocalDate cutoff = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(posts.statsRetention().toDays());
        int batch = jobs.purgeBatchSize();
        long total = 0;
        while (true) {
            Integer deleted = transactionTemplate.execute(status -> repository.deleteOlderThan(cutoff, batch));
            int count = deleted == null ? 0 : deleted;
            total += count;
            if (count < batch) {
                break;
            }
        }
        log.info("Post stats purge finished: rows={}, before={}", total, cutoff);
        return total;
    }
}
