package net.java21.blog.backend.export.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipInputStream;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.export.ExportProperties;
import net.java21.blog.backend.export.domain.BlogExport;
import net.java21.blog.backend.export.domain.ExportStatus;
import net.java21.blog.backend.export.event.BlogExportReadyEvent;
import net.java21.blog.backend.export.repository.BlogExportQueryRepository;
import net.java21.blog.backend.export.repository.BlogExportRepository;
import net.java21.blog.backend.export.repository.ExportPostQueryRepository;
import net.java21.blog.backend.export.service.BlogExportWriter;
import net.java21.blog.backend.export.storage.ExportStorage;
import net.java21.blog.backend.media.storage.MediaStorage;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 백업 생성·정리·기동 복구(T102, FR-145, research B14): PENDING 하나씩 RUNNING으로 가져와 READY(완료·만료 +7일·크기)와
 * {@link BlogExportReadyEvent}, 쓰기 실패면 부분 파일을 지우고 FAILED·{@code error_code}; 만료된 READY는 파일을 지우고
 * EXPIRED(파일이 이미 없어도); 기동 때 1시간 넘은 RUNNING은 FAILED {@code INTERRUPTED}.
 */
@JpaRepositoryTest
@RecordApplicationEvents
@ExtendWith(OutputCaptureExtension.class)
@Import({BlogExportQueryRepository.class, ExportPostQueryRepository.class, TagQueryRepository.class})
class BlogExportJobTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private BlogExportRepository exportRepository;
    @Autowired
    private BlogExportQueryRepository queryRepository;
    @Autowired
    private ExportPostQueryRepository postQueryRepository;
    @Autowired
    private TagQueryRepository tagQueryRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private ApplicationEvents recorded;

    @TempDir
    Path exportDir;

    private final MutableClock clock = new MutableClock(NOW);
    private ExportProperties properties;
    private ExportStorage storage;
    private JpaFixtures fx;
    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        properties = new ExportProperties(exportDir.toString(), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofHours(1));
        storage = new ExportStorage(properties);
        fx = new JpaFixtures(em);
        owner = fx.user("백업 작업");
        blog = fx.blog(owner, "jobblog");
        fx.published(blog, "글 하나", null, 1);
    }

    @Test
    void pendingBecomesReadyWithFileAndEvent(CapturedOutput output) throws IOException {
        BlogExport first = pending();
        clock.advance(Duration.ofSeconds(1));
        BlogExport second = pending();
        fx.flushAndClear();
        clock.advance(Duration.ofSeconds(30));

        assertThat(job(realWriter()).runPending()).isEqualTo(2);
        fx.flushAndClear();

        BlogExport ready = em.find(BlogExport.class, first.getId());
        assertThat(ready.getStatus()).isEqualTo(ExportStatus.READY);
        assertThat(ready.getCompletedAt()).isEqualTo(clock.instant());
        assertThat(ready.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(ready.getFilePath()).matches("2026/10/[0-9a-f-]{36}\\.zip");
        Path file = exportDir.resolve(ready.getFilePath());
        assertThat(ready.getFileSize()).isEqualTo(Files.size(file)).isPositive();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(file))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("blog.json");
        }
        assertThat(em.find(BlogExport.class, second.getId()).getStatus()).isEqualTo(ExportStatus.READY);

        List<BlogExportReadyEvent> published = recorded.stream(BlogExportReadyEvent.class).toList();
        assertThat(published).hasSize(2);
        assertThat(published.getFirst()).isEqualTo(new BlogExportReadyEvent(first.getId(), owner.getId(), blog.getId(),
                blog.getTitle(), "jobblog", clock.instant().plus(Duration.ofDays(7))));
        assertThat(output).contains("Blog export ready: exportId=" + first.getId());

        assertThat(job(realWriter()).runPending()).as("남은 PENDING 없음").isZero();
    }

    @Test
    void writeFailureMarksFailedAndDeletesThePartialFile() {
        BlogExport export = pending();
        fx.flushAndClear();
        BlogExportWriter failing = new BlogExportWriter(null, null, null, null, clock) {
            @Override
            public Summary write(Blog target, OutputStream out) throws IOException {
                out.write(new byte[] {'P', 'K'});
                throw new IOException("disk full");
            }
        };

        assertThat(job(failing).runPending()).isZero();
        fx.flushAndClear();

        BlogExport failed = em.find(BlogExport.class, export.getId());
        assertThat(failed.getStatus()).isEqualTo(ExportStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo(BlogExportJob.IO_ERROR);
        assertThat(failed.getFilePath()).isNull();
        assertThat(recorded.stream(BlogExportReadyEvent.class)).isEmpty();
        assertThat(zipFiles()).as("부분 파일은 지운다").isEmpty();
    }

    @Test
    void unexpectedErrorIsInternalError() {
        BlogExport export = pending();
        fx.flushAndClear();
        BlogExportWriter broken = new BlogExportWriter(null, null, null, null, clock) {
            @Override
            public Summary write(Blog target, OutputStream out) {
                throw new IllegalStateException("bug");
            }
        };

        job(broken).runPending();
        fx.flushAndClear();

        assertThat(em.find(BlogExport.class, export.getId()).getErrorCode()).isEqualTo(BlogExportJob.INTERNAL_ERROR);
    }

    @Test
    void cleanupExpiresReadyExportsAndDeletesFiles() throws IOException {
        BlogExport expired = pending();
        expired.markReady("2026/10/old.zip", 3, NOW.minus(Duration.ofDays(8)), Duration.ofDays(7));
        BlogExport alreadyGone = pending();
        alreadyGone.markReady("2026/10/gone.zip", 3, NOW.minus(Duration.ofDays(8)), Duration.ofDays(7));
        BlogExport fresh = pending();
        fresh.markReady("2026/10/fresh.zip", 3, NOW, Duration.ofDays(7));
        writeFile("2026/10/old.zip");
        writeFile("2026/10/fresh.zip");
        fx.flushAndClear();

        BlogExportCleanupJob cleanup = new BlogExportCleanupJob(queryRepository, storage,
                new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 1), transactionManager, clock);
        assertThat(cleanup.cleanup()).isEqualTo(2);
        fx.flushAndClear();

        assertThat(em.find(BlogExport.class, expired.getId()).getStatus()).isEqualTo(ExportStatus.EXPIRED);
        assertThat(em.find(BlogExport.class, expired.getId()).getFilePath()).isNull();
        assertThat(em.find(BlogExport.class, alreadyGone.getId()).getStatus()).isEqualTo(ExportStatus.EXPIRED);
        assertThat(em.find(BlogExport.class, fresh.getId()).getStatus()).isEqualTo(ExportStatus.READY);
        assertThat(Files.exists(exportDir.resolve("2026/10/old.zip"))).isFalse();
        assertThat(Files.exists(exportDir.resolve("2026/10/fresh.zip"))).isTrue();
        assertThat(cleanup.cleanup()).isZero();
    }

    @Test
    void startupMarksLongRunningExportsInterrupted() throws IOException {
        BlogExport stale = pending();
        BlogExport recent = pending();
        fx.flushAndClear();
        queryRepository.claim(stale.getId(), "2026/10/stale.zip", NOW.minus(Duration.ofHours(2)));
        queryRepository.claim(recent.getId(), "2026/10/recent.zip", NOW.minus(Duration.ofMinutes(10)));
        writeFile("2026/10/stale.zip");
        em.clear();

        StaleExportRecovery recovery = new StaleExportRecovery(queryRepository, storage, properties,
                transactionManager, clock);
        recovery.run(null);
        fx.flushAndClear();

        BlogExport interrupted = em.find(BlogExport.class, stale.getId());
        assertThat(interrupted.getStatus()).isEqualTo(ExportStatus.FAILED);
        assertThat(interrupted.getErrorCode()).isEqualTo(StaleExportRecovery.INTERRUPTED);
        assertThat(Files.exists(exportDir.resolve("2026/10/stale.zip"))).isFalse();
        assertThat(em.find(BlogExport.class, recent.getId()).getStatus()).isEqualTo(ExportStatus.RUNNING);
        assertThat(recovery.recover()).isZero();
    }

    private BlogExportJob job(BlogExportWriter writer) {
        return new BlogExportJob(exportRepository, queryRepository, writer, storage, properties, events,
                transactionManager, clock);
    }

    private BlogExportWriter realWriter() {
        return new BlogExportWriter(postQueryRepository, tagQueryRepository, Mockito.mock(MediaStorage.class), em,
                clock);
    }

    private BlogExport pending() {
        BlogExport export = new BlogExport(blog, owner);
        em.persist(export);
        return export;
    }

    private void writeFile(String relative) throws IOException {
        Path file = exportDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "PK");
    }

    private List<Path> zipFiles() {
        try (var paths = Files.walk(exportDir)) {
            return paths.filter(p -> p.toString().endsWith(".zip")).toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
