package net.java21.blog.backend.media.job;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.media.MediaProperties;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.media.repository.CleanupCandidate;
import net.java21.blog.backend.media.repository.MediaQueryRepository;
import net.java21.blog.backend.media.storage.MediaStorage;
import net.java21.blog.backend.media.thumbnail.ThumbnailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이미지 정리(T219, FR-072·073, research R11). {@code blog.media.cleanup-cron}(기본 매시 정각)마다
 * {@code created_at < 지금 - blog.media.temp-ttl}인 TEMP와 ORPHANED를 행 → 원본 파일 → 썸네일 디렉터리 순으로 지운다.
 * 행은 고른 뒤 상태가 그대로일 때만 지우므로(그 사이 다시 참조돼 ATTACHED가 됐으면 남김) 쓰이는 파일을 지우지 않는다.
 * {@code blog.jobs.purge-batch-size}건씩 처리하고 건수를 로그에 남긴다.
 */
@Component
public class MediaCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(MediaCleanupJob.class);

    private final MediaQueryRepository repository;
    private final MediaStorage storage;
    private final ThumbnailService thumbnailService;
    private final TransactionTemplate transactionTemplate;
    private final MediaProperties properties;
    private final JobsProperties jobs;
    private final Clock clock;

    public MediaCleanupJob(MediaQueryRepository repository, MediaStorage storage, ThumbnailService thumbnailService,
            TransactionTemplate transactionTemplate, MediaProperties properties, JobsProperties jobs, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.thumbnailService = thumbnailService;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.jobs = jobs;
        this.clock = clock;
    }

    /** 처리 결과(지운 TEMP 수, 지운 ORPHANED 수). */
    public record Result(long temp, long orphaned) {
    }

    @Scheduled(cron = "${blog.media.cleanup-cron:0 0 * * * *}")
    public void run() {
        cleanup();
    }

    public Result cleanup() {
        Instant cutoff = clock.instant().minus(properties.tempTtl());
        long temp = 0;
        long orphaned = 0;
        while (true) {
            List<CleanupCandidate> batch = transactionTemplate.execute(
                    status -> repository.findCleanupCandidates(cutoff, jobs.purgeBatchSize()));
            if (batch == null || batch.isEmpty()) {
                break;
            }
            long deletedInBatch = 0;
            for (CleanupCandidate candidate : batch) {
                if (delete(candidate)) {
                    deletedInBatch++;
                    if (candidate.status() == MediaStatus.TEMP) {
                        temp++;
                    } else {
                        orphaned++;
                    }
                }
            }
            if (batch.size() < jobs.purgeBatchSize() || deletedInBatch == 0) {
                break;
            }
        }
        log.info("Media cleanup finished: temp={}, orphaned={}, tempCutoff={}", temp, orphaned, cutoff);
        return new Result(temp, orphaned);
    }

    private boolean delete(CleanupCandidate candidate) {
        Long deleted = transactionTemplate.execute(
                status -> repository.deleteIfStill(candidate.id(), candidate.status()));
        if (deleted == null || deleted == 0) {
            return false;
        }
        MediaStorage.Area area = candidate.status() == MediaStatus.TEMP
                ? MediaStorage.Area.TEMP : MediaStorage.Area.UPLOAD;
        try {
            storage.delete(area, candidate.storedPath());
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete media file {} ({})", candidate.storedPath(), candidate.mediaKey(), e);
        }
        thumbnailService.deleteAll(candidate.mediaKey());
        return true;
    }
}
