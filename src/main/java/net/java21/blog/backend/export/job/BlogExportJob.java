package net.java21.blog.backend.export.job;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.export.ExportProperties;
import net.java21.blog.backend.export.domain.BlogExport;
import net.java21.blog.backend.export.event.BlogExportReadyEvent;
import net.java21.blog.backend.export.repository.BlogExportQueryRepository;
import net.java21.blog.backend.export.repository.BlogExportRepository;
import net.java21.blog.backend.export.service.BlogExportWriter;
import net.java21.blog.backend.export.storage.ExportStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 백업 생성(004 FR-145, research B14). {@code blog.jobs.export-poll-delay}(기본 30초)마다 가장 오래된 PENDING을 하나씩
 * 조건부 UPDATE로 RUNNING으로 가져와(쓸 파일 경로를 함께 적음) 만든다. 한 번 실행에서 남은 PENDING을 차례로 처리하되 동시에 둘을
 * 만들지 않는다(별도 스레드 풀 없음). 성공하면 READY(완료·만료 시각, 크기)와 {@link BlogExportReadyEvent}(커밋 뒤 알림),
 * 실패하면 부분 파일을 지우고 FAILED({@code IO_ERROR} 등, 하루 제한에 세지 않음).
 */
@Component
public class BlogExportJob {

    /** 한 번 실행에서 만드는 최대 수(오래 붙잡지 않도록). */
    static final int MAX_PER_RUN = 10;
    static final String IO_ERROR = "IO_ERROR";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Logger log = LoggerFactory.getLogger(BlogExportJob.class);

    private final BlogExportRepository exportRepository;
    private final BlogExportQueryRepository queryRepository;
    private final BlogExportWriter writer;
    private final ExportStorage storage;
    private final ExportProperties properties;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readOnly;
    private final Clock clock;

    public BlogExportJob(BlogExportRepository exportRepository, BlogExportQueryRepository queryRepository,
            BlogExportWriter writer, ExportStorage storage, ExportProperties properties,
            ApplicationEventPublisher events, PlatformTransactionManager transactionManager, Clock clock) {
        this.exportRepository = exportRepository;
        this.queryRepository = queryRepository;
        this.writer = writer;
        this.storage = storage;
        this.properties = properties;
        this.events = events;
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${blog.jobs.export-poll-delay:30s}",
            initialDelayString = "${blog.jobs.export-poll-delay:30s}")
    public void run() {
        runPending();
    }

    /** 남은 PENDING을 차례로(최대 {@link #MAX_PER_RUN}개) 만든다. 만든(READY가 된) 수. */
    public int runPending() {
        int ready = 0;
        for (int i = 0; i < MAX_PER_RUN; i++) {
            Claimed claimed = claimNext();
            if (claimed == null) {
                break;
            }
            if (build(claimed)) {
                ready++;
            }
        }
        return ready;
    }

    /** 가져온 백업: id와 쓸 파일 경로. */
    record Claimed(Long exportId, String filePath) {
    }

    /** 가장 오래된 PENDING을 RUNNING으로. 다른 실행이 먼저 가져갔으면 다음 것을, 없으면 null. */
    Claimed claimNext() {
        return transaction.execute(status -> {
            for (int attempt = 0; attempt < 3; attempt++) {
                Long id = queryRepository.findOldestPendingId();
                if (id == null) {
                    return null;
                }
                Instant now = clock.instant();
                String path = storage.newPath(now);
                if (queryRepository.claim(id, path, now) == 1) {
                    return new Claimed(id, path);
                }
            }
            return null;
        });
    }

    private boolean build(Claimed claimed) {
        try {
            readOnly.executeWithoutResult(status -> {
                BlogExport export = exportRepository.findById(claimed.exportId()).orElseThrow();
                Blog blog = export.getBlog();
                try (OutputStream out = storage.create(claimed.filePath())) {
                    writer.write(blog, out);
                } catch (IOException e) {
                    throw new ExportIoException(e);
                }
            });
            long size = storage.size(claimed.filePath());
            transaction.executeWithoutResult(status -> markReady(claimed, size));
            log.info("Blog export ready: exportId={}, size={}", claimed.exportId(), size);
            return true;
        } catch (ExportIoException | IOException e) {
            fail(claimed, IO_ERROR, e);
        } catch (RuntimeException e) {
            fail(claimed, INTERNAL_ERROR, e);
        }
        return false;
    }

    private void markReady(Claimed claimed, long size) {
        BlogExport export = exportRepository.findById(claimed.exportId()).orElseThrow();
        Instant now = clock.instant();
        export.markReady(claimed.filePath(), size, now, properties.retention());
        Blog blog = export.getBlog();
        events.publishEvent(new BlogExportReadyEvent(export.getId(), export.getRequestedBy().getId(), blog.getId(),
                blog.getTitle(), blog.getHandle(), export.getExpiresAt()));
    }

    private void fail(Claimed claimed, String errorCode, Exception cause) {
        log.warn("Blog export failed: exportId={}, errorCode={}, error={}", claimed.exportId(), errorCode,
                cause.toString());
        try {
            storage.delete(claimed.filePath());
        } catch (IOException | RuntimeException e) {
            log.warn("Partial export file was not deleted: exportId={}, error={}", claimed.exportId(), e.toString());
        }
        transaction.executeWithoutResult(status -> exportRepository.findById(claimed.exportId())
                .ifPresent(export -> export.markFailed(errorCode)));
    }

    /** 쓰기 중 입출력 오류(트랜잭션 콜백 밖으로 꺼내기 위한 감싸기). */
    static final class ExportIoException extends RuntimeException {
        ExportIoException(IOException cause) {
            super(cause);
        }
    }
}
