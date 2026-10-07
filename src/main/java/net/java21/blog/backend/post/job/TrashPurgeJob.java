package net.java21.blog.backend.post.job;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.export.storage.ExportStorage;
import net.java21.blog.backend.media.service.MediaReferenceService;
import net.java21.blog.backend.post.repository.TrashPurgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 휴지통 비우기(T103, FR-084, FR-159, research R26). {@code blog.jobs.trash-purge-cron}(기본 매일 03:30)마다
 * <ol>
 *   <li>{@code deleted_at < 지금 - blog.jobs.trash-retention(30일)}인 휴지통 글을 영구 삭제하고</li>
 *   <li>같은 기간이 지난 삭제된 블로그의 카테고리·구독·방명록·사이드바 설정·일별 방문·차단·백업 행(004, 백업 파일은 커밋 뒤)을 지우고 제목·소개를 비운다({@code blogs} 행은 주소 재사용 방지로 남김).</li>
 * </ol>
 * {@code blog.jobs.purge-batch-size}건씩 트랜잭션을 나눠 처리하고 처리 건수를 로그에 남긴다. 서버 1대 전제라 분산 락은 없다.
 * 같은 트랜잭션에서 영구 삭제한 글의 이미지 참조({@code post_media})와 비운 블로그의 대표 이미지를 정리 대상 판단한다(US4, FR-073).
 */
@Component
public class TrashPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(TrashPurgeJob.class);

    private final TrashPurgeRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final JobsProperties properties;
    private final Clock clock;
    /** 이미지 참조 정리. 없으면(이미지를 다루지 않는 슬라이스 테스트) 건너뛴다. */
    private final MediaReferenceService mediaReferences;
    /** 백업 파일 보관(004). 없으면(슬라이스 테스트) 파일은 건너뛰고 행만 지운다. */
    private final ExportStorage exportStorage;

    public TrashPurgeJob(TrashPurgeRepository repository, TransactionTemplate transactionTemplate,
            JobsProperties properties, Clock clock) {
        this(repository, transactionTemplate, properties, clock, null, null);
    }

    public TrashPurgeJob(TrashPurgeRepository repository, TransactionTemplate transactionTemplate,
            JobsProperties properties, Clock clock, MediaReferenceService mediaReferences) {
        this(repository, transactionTemplate, properties, clock, mediaReferences, null);
    }

    @Autowired
    public TrashPurgeJob(TrashPurgeRepository repository, TransactionTemplate transactionTemplate,
            JobsProperties properties, Clock clock, MediaReferenceService mediaReferences,
            ExportStorage exportStorage) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
        this.mediaReferences = mediaReferences;
        this.exportStorage = exportStorage;
    }

    /** 처리 결과(영구 삭제한 글 수, 비운 블로그 수). */
    public record Result(long posts, long blogs) {
    }

    @Scheduled(cron = "${blog.jobs.trash-purge-cron:0 30 3 * * *}")
    public void run() {
        purge();
    }

    public Result purge() {
        Instant cutoff = clock.instant().minus(properties.trashRetention());
        int batch = properties.purgeBatchSize();
        long posts = inBatches(() -> repository.findPurgeablePostIds(cutoff, batch), this::deletePosts);
        long blogs = inBatches(() -> repository.findPurgeableBlogIds(cutoff, batch), this::purgeBlogs);
        log.info("Trash purge finished: posts={}, blogs={}, cutoff={}", posts, blogs, cutoff);
        return new Result(posts, blogs);
    }

    /** 글 영구 삭제: 이미지 참조를 먼저 지우고, 삭제 뒤 그 이미지들을 정리 대상 판단. */
    private long deletePosts(List<Long> ids) {
        List<Long> mediaIds = mediaReferences == null ? List.of() : mediaReferences.detachPosts(ids);
        long deleted = repository.deletePosts(ids);
        if (mediaReferences != null) {
            mediaReferences.reevaluate(mediaIds);
        }
        return deleted;
    }

    /**
     * 삭제된 블로그 비우기: 대표 이미지 참조가 사라지므로 비운 뒤 정리 대상 판단. 백업(004) 파일은 행을 지운 트랜잭션이 커밋된 뒤 지운다
     * (롤백되면 파일이 남아 다음 실행이 다시 지운다).
     */
    private long purgeBlogs(List<Long> ids) {
        List<Long> coverIds = mediaReferences == null ? List.of() : mediaReferences.coverMediaIds(ids);
        List<String> exportFiles = repository.findExportFiles(ids);
        long purged = repository.purgeBlogs(ids);
        deleteExportFilesAfterCommit(exportFiles);
        if (mediaReferences != null) {
            mediaReferences.reevaluate(coverIds);
        }
        return purged;
    }

    private void deleteExportFilesAfterCommit(List<String> files) {
        if (exportStorage == null || files.isEmpty()) {
            return;
        }
        Runnable delete = () -> {
            for (String file : files) {
                try {
                    exportStorage.delete(file);
                } catch (IOException | RuntimeException e) {
                    log.warn("Export file of a purged blog was not deleted: path={}, error={}", file, e.toString());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }

    /**
     * 고르기 → 처리를 한 트랜잭션으로, 고른 수가 배치 크기보다 작아질 때까지 되풀이한다.
     * 고른 것을 하나도 처리하지 못했으면(다른 곳에서 상태가 바뀜) 같은 행을 되풀이하지 않도록 멈춘다.
     */
    private long inBatches(Supplier<List<Long>> select, Function<List<Long>, Long> process) {
        long total = 0;
        while (true) {
            long[] batch = transactionTemplate.execute(status -> {
                List<Long> ids = select.get();
                return new long[] {ids.size(), ids.isEmpty() ? 0 : process.apply(ids)};
            });
            if (batch == null) {
                return total;
            }
            total += batch[1];
            if (batch[0] < properties.purgeBatchSize() || batch[1] == 0) {
                return total;
            }
        }
    }
}
