package net.java21.blog.backend.admin.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.admin.AdminProperties;
import net.java21.blog.backend.common.job.JobsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 작업 기록 정리(006 FR-106 "1년간 보관", research A7). {@code blog.jobs.audit-purge-cron}(기본 매일 05:15)마다 남긴 지
 * {@code blog.admin.audit-retention}(기본 365일)이 지난 기록을 {@code blog.jobs.purge-batch-size}건씩 트랜잭션을 나눠 지우고
 * 건수만 로그에 남긴다.
 */
@Component
public class AdminAuditPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditPurgeJob.class);

    private final AdminAuditPurgeRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final AdminProperties adminProperties;
    private final JobsProperties jobs;
    private final Clock clock;

    public AdminAuditPurgeJob(AdminAuditPurgeRepository repository, TransactionTemplate transactionTemplate,
            AdminProperties adminProperties, JobsProperties jobs, Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.adminProperties = adminProperties;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Scheduled(cron = "${blog.jobs.audit-purge-cron:" + JobsProperties.DEFAULT_AUDIT_PURGE_CRON + "}")
    public void run() {
        purge();
    }

    /** @return 지운 기록 수 */
    public long purge() {
        Instant cutoff = clock.instant().minus(adminProperties.auditRetention());
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
        log.info("Admin audit log purge finished: logs={}, cutoff={}", total, cutoff);
        return total;
    }
}
