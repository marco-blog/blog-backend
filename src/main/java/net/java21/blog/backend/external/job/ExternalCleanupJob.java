package net.java21.blog.backend.external.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.release.ExternalPostPurger;
import net.java21.blog.backend.external.repository.ExternalBlogVerificationRepository;
import net.java21.blog.backend.external.repository.ExternalPostDailyClickRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository.PurgeRow;
import net.java21.blog.backend.external.thumbnail.ExternalThumbnailService;
import net.java21.blog.backend.media.service.MediaKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 007 정리 작업(research E15, 기본 매일 05:30). 한 번에 {@value #BATCH}건씩 트랜잭션을 나눠 지운다.
 * <ol>
 *   <li>{@code expires_at}이 7일 지난 인증 코드(성공한 행 포함 — 결과는 등록의 {@code ownership_verified}에 있음)</li>
 *   <li>해제된 등록의 {@code REMOVED} 글 중 내린 지({@code updated_at}) {@code release-retention}(30일)이 지난 것(탈퇴·신고 처리
 *   근거 보관). 주인이 남긴 {@code ACTIVE} 글은 기한 없이 남는다(결정 표 24번). 포털 제외 행 먼저, 검수·일별 클릭은 DB CASCADE,
 *   썸네일 파일은 커밋 뒤.</li>
 *   <li>90일 지난 일별 클릭</li>
 *   <li>월요일 실행분만: {@code thumbnail-dir/external/} 아래 DB에 없는 키의 파일(커밋 뒤 삭제를 놓친 파일)</li>
 * </ol>
 */
@Component
public class ExternalCleanupJob {

    static final int BATCH = 500;
    static final Duration VERIFICATION_GRACE = Duration.ofDays(7);
    static final int CLICK_RETENTION_DAYS = 90;
    /** 이 루프 상한을 넘으면 다음 실행에 넘긴다(한 번에 너무 오래 돌지 않게). */
    static final int MAX_ROUNDS = 200;

    private static final Logger log = LoggerFactory.getLogger(ExternalCleanupJob.class);

    public record Summary(int verifications, int posts, int clicks, int orphanFiles) {
    }

    private final ExternalBlogVerificationRepository verificationRepository;
    private final ExternalPostRepository postRepository;
    private final ExternalPostDailyClickRepository clickRepository;
    private final ExternalPostPurger purger;
    private final ExternalThumbnailService thumbnails;
    private final ExternalFeedProperties properties;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ExternalCleanupJob(ExternalBlogVerificationRepository verificationRepository,
            ExternalPostRepository postRepository, ExternalPostDailyClickRepository clickRepository,
            ExternalPostPurger purger, ExternalThumbnailService thumbnails, ExternalFeedProperties properties,
            TransactionTemplate tx, Clock clock) {
        this.verificationRepository = verificationRepository;
        this.postRepository = postRepository;
        this.clickRepository = clickRepository;
        this.purger = purger;
        this.thumbnails = thumbnails;
        this.properties = properties;
        this.tx = tx;
        this.clock = clock;
    }

    @Scheduled(cron = "${blog.external.cleanup-cron:0 30 5 * * *}")
    public void run() {
        Summary summary = runOnce();
        log.info("External cleanup: verifications={}, posts={}, clicks={}, orphanFiles={}", summary.verifications(),
                summary.posts(), summary.clicks(), summary.orphanFiles());
    }

    public Summary runOnce() {
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(clock.getZone());
        Instant verificationCutoff = now.minus(VERIFICATION_GRACE);
        int verifications = repeat(() -> verificationRepository.deleteExpiredBefore(verificationCutoff, BATCH));
        Instant postCutoff = local.minus(properties.releaseRetention()).toInstant();
        int posts = repeat(() -> purger.purge(postRepository.findExpiredRemoved(ExternalBlogStatus.RELEASED,
                ExternalPostStatus.REMOVED, postCutoff, Limit.of(BATCH))));
        LocalDate clickCutoff = local.toLocalDate().minusDays(CLICK_RETENTION_DAYS);
        int clicks = repeat(() -> clickRepository.deleteOlderThan(clickCutoff, BATCH));
        int orphans = local.getDayOfWeek() == DayOfWeek.MONDAY ? deleteOrphanFiles() : 0;
        return new Summary(verifications, posts, clicks, orphans);
    }

    /** 한 트랜잭션에 한 묶음씩, 묶음이 {@value #BATCH}보다 작아질 때까지. */
    private int repeat(IntSupplier step) {
        int total = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            Integer done = tx.execute(status -> step.getAsInt());
            int count = done == null ? 0 : done;
            total += count;
            if (count < BATCH) {
                break;
            }
        }
        return total;
    }

    /** {@code external/} 아래 파일 중 DB에 키가 없는 것을 지운다. 키 모양이 아닌 파일은 건드리지 않는다. */
    int deleteOrphanFiles() {
        Path root = thumbnails.root();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        Map<String, List<Path>> byKey;
        try (Stream<Path> files = Files.walk(root, 2)) {
            byKey = files.filter(Files::isRegularFile)
                    .filter(path -> MediaKeyGenerator.isKey(keyOf(path)))
                    .collect(Collectors.groupingBy(ExternalCleanupJob::keyOf));
        } catch (IOException e) {
            log.warn("Could not list external thumbnails under {}", root, e);
            return 0;
        }
        List<String> keys = new ArrayList<>(byKey.keySet());
        Set<String> existing = new HashSet<>();
        for (int from = 0; from < keys.size(); from += BATCH) {
            existing.addAll(postRepository.findExistingThumbnailKeys(
                    keys.subList(from, Math.min(keys.size(), from + BATCH))));
        }
        int deleted = 0;
        for (Map.Entry<String, List<Path>> entry : byKey.entrySet()) {
            if (existing.contains(entry.getKey())) {
                continue;
            }
            for (Path path : entry.getValue()) {
                try {
                    if (Files.deleteIfExists(path)) {
                        deleted++;
                    }
                } catch (IOException e) {
                    log.warn("Could not delete orphan external thumbnail {}", path, e);
                }
            }
        }
        return deleted;
    }

    private static String keyOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}
