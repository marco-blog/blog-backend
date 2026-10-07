package net.java21.blog.backend.export.job;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.export.domain.BlogExport;
import net.java21.blog.backend.export.repository.BlogExportQueryRepository;
import net.java21.blog.backend.export.storage.ExportStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료된 백업 정리(004 research B14). {@code blog.jobs.export-cleanup-cron}(기본 매시 10분)마다 {@code expires_at}이 지난 READY의
 * 파일을 지우고 EXPIRED로 바꾼다. 파일이 이미 없어도 EXPIRED로 바꾼다. 지우지 못한 파일은 READY로 두고 다음 실행에 다시 한다.
 * {@code blog.jobs.purge-batch-size}건씩 나눠 처리한다.
 */
@Component
public class BlogExportCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(BlogExportCleanupJob.class);

    private final BlogExportQueryRepository queryRepository;
    private final ExportStorage storage;
    private final JobsProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public BlogExportCleanupJob(BlogExportQueryRepository queryRepository, ExportStorage storage,
            JobsProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.queryRepository = queryRepository;
        this.storage = storage;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(cron = "${blog.jobs.export-cleanup-cron:0 10 * * * *}")
    public void run() {
        cleanup();
    }

    /** 만료 처리한 수. */
    public long cleanup() {
        Instant now = clock.instant();
        int batch = properties.purgeBatchSize();
        long total = 0;
        while (true) {
            long[] result = transaction.execute(status -> {
                List<BlogExport> expired = queryRepository.findExpiredReady(now, batch);
                long done = 0;
                for (BlogExport export : expired) {
                    try {
                        storage.delete(export.getFilePath());
                        export.markExpired();
                        done++;
                    } catch (IOException | RuntimeException e) {
                        log.warn("Expired export file was not deleted: exportId={}, error={}", export.getId(),
                                e.toString());
                    }
                }
                return new long[] {expired.size(), done};
            });
            if (result == null) {
                break;
            }
            total += result[1];
            if (result[0] < batch || result[1] == 0) {
                break;
            }
        }
        if (total > 0) {
            log.info("Expired blog exports cleaned up: count={}", total);
        }
        return total;
    }
}
