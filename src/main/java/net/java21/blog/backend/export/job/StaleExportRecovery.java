package net.java21.blog.backend.export.job;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.export.ExportProperties;
import net.java21.blog.backend.export.domain.BlogExport;
import net.java21.blog.backend.export.repository.BlogExportQueryRepository;
import net.java21.blog.backend.export.storage.ExportStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 기동 때 끊긴 백업 정리(004 research B14). {@code blog.export.stale-running}(1시간)보다 오래 RUNNING인 행은 서버가 만들다
 * 멈춘 것으로 보고 부분 파일을 지운 뒤 FAILED({@code INTERRUPTED})로 바꾼다(하루 제한에 세지 않음).
 */
@Component
public class StaleExportRecovery implements ApplicationRunner {

    static final String INTERRUPTED = "INTERRUPTED";

    private static final Logger log = LoggerFactory.getLogger(StaleExportRecovery.class);

    private final BlogExportQueryRepository queryRepository;
    private final ExportStorage storage;
    private final ExportProperties properties;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public StaleExportRecovery(BlogExportQueryRepository queryRepository, ExportStorage storage,
            ExportProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.queryRepository = queryRepository;
        this.storage = storage;
        this.properties = properties;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        recover();
    }

    /** FAILED로 바꾼 수. */
    public int recover() {
        Instant before = clock.instant().minus(properties.staleRunning());
        Integer count = transaction.execute(status -> {
            List<BlogExport> stale = queryRepository.findStaleRunning(before);
            for (BlogExport export : stale) {
                try {
                    storage.delete(export.getFilePath());
                } catch (IOException | RuntimeException e) {
                    log.warn("Interrupted export file was not deleted: exportId={}, error={}", export.getId(),
                            e.toString());
                }
                export.markFailed(INTERRUPTED);
            }
            return stale.size();
        });
        int recovered = count == null ? 0 : count;
        if (recovered > 0) {
            log.info("Interrupted blog exports marked failed: count={}", recovered);
        }
        return recovered;
    }
}
