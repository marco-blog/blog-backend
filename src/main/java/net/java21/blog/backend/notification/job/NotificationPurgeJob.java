package net.java21.blog.backend.notification.job;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.notification.NotificationsProperties;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 오래된 알림 정리(002 data-model notifications, research D3). {@code blog.jobs.notification-purge-cron}(기본 매일 04:15)마다
 * 만든 지 {@code blog.notifications.retention}(90일)이 지난 알림을 {@code blog.jobs.purge-batch-size}건씩 트랜잭션을
 * 나눠 지우고 건수를 로그에 남긴다.
 */
@Component
public class NotificationPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationPurgeJob.class);

    private final NotificationQueryRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final NotificationsProperties notifications;
    private final JobsProperties jobs;
    private final Clock clock;

    public NotificationPurgeJob(NotificationQueryRepository repository, TransactionTemplate transactionTemplate,
            NotificationsProperties notifications, JobsProperties jobs, Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.notifications = notifications;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Scheduled(cron = "${blog.jobs.notification-purge-cron:" + JobsProperties.DEFAULT_NOTIFICATION_PURGE_CRON + "}")
    public void run() {
        purge();
    }

    /** @return 지운 알림 수 */
    public long purge() {
        Instant cutoff = clock.instant().minus(notifications.retention());
        int batch = jobs.purgeBatchSize();
        long total = 0;
        while (true) {
            long[] result = transactionTemplate.execute(status -> {
                List<Long> ids = repository.findIdsCreatedBefore(cutoff, batch);
                return new long[] {ids.size(), repository.deleteByIds(ids)};
            });
            if (result == null) {
                break;
            }
            total += result[1];
            if (result[0] < batch || result[1] == 0) {
                break;
            }
        }
        log.info("Notification purge finished: notifications={}, cutoff={}", total, cutoff);
        return total;
    }
}
