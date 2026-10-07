package net.java21.blog.backend.post.job;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.post.repository.ScheduledPublishRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예약 발행(004 FR-064, SC-010, research B5). {@code blog.jobs.scheduled-publish-delay}(기본 30초)마다 예약 시각이 지난 예약 글을
 * {@code blog.jobs.purge-batch-size}개씩 읽어 묶음마다 한 트랜잭션에서 글마다 조건부 UPDATE로 발행한다. 실제로 발행한 글(1행)만
 * 블로그 첫 발행 시각을 채운다. 주기가 30초라 예약 시각부터 1분 안에 발행된다. 서버 1대 전제라 분산 락은 없다(001 R26).
 * <p>005가 예약 발행 때 트랙백 보내기를 붙이는 자리는 {@link #publish}의 "발행됨" 분기다.
 */
@Component
public class ScheduledPublishJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPublishJob.class);

    private final ScheduledPublishRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final JobsProperties properties;
    private final Clock clock;

    public ScheduledPublishJob(ScheduledPublishRepository repository, TransactionTemplate transactionTemplate,
            JobsProperties properties, Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${blog.jobs.scheduled-publish-delay:30s}",
            initialDelayString = "${blog.jobs.scheduled-publish-delay:30s}")
    public void run() {
        publishDue();
    }

    /** 지금까지 예약 시각이 지난 글을 모두 발행하고 발행한 수를 돌려준다. */
    public long publishDue() {
        Instant now = clock.instant();
        int batch = properties.purgeBatchSize();
        long total = 0;
        while (true) {
            long[] result = transactionTemplate.execute(status -> {
                List<Long> ids = repository.findDueIds(now, batch);
                long published = 0;
                for (Long id : ids) {
                    published += publish(id, now);
                }
                return new long[] {ids.size(), published};
            });
            if (result == null) {
                break;
            }
            total += result[1];
            // 고른 것을 하나도 발행하지 못했으면(그 사이 상태가 바뀜) 같은 행을 되풀이하지 않도록 멈춘다.
            if (result[0] < batch || result[1] == 0) {
                break;
            }
        }
        if (total > 0) {
            log.info("Scheduled publish finished: published={}, now={}", total, now);
        }
        return total;
    }

    private long publish(Long postId, Instant now) {
        if (repository.publishIfDue(postId, now) == 0) {
            return 0;
        }
        repository.markBlogFirstPublished(postId, now);
        // 005: 예약 글이 발행된 이 지점에 트랙백 보내기를 붙인다.
        return 1;
    }
}
