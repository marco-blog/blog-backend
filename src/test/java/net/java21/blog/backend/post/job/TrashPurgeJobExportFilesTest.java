package net.java21.blog.backend.post.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.export.ExportProperties;
import net.java21.blog.backend.export.storage.ExportStorage;
import net.java21.blog.backend.post.repository.TrashPurgeRepository;
import net.java21.blog.backend.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 블로그 정리의 백업 파일 삭제(T110, research B16): 행을 지운 트랜잭션이 커밋된 뒤에 파일을 지우고, 롤백되면 파일을 남긴다.
 */
@ExtendWith(MockitoExtension.class)
class TrashPurgeJobExportFilesTest {

    private static final Instant NOW = Instant.parse("2026-10-06T03:30:00Z");

    @Mock
    private TrashPurgeRepository repository;

    @TempDir
    Path exportDir;

    @Test
    void exportFilesAreDeletedAfterCommit() throws IOException {
        ExportStorage storage = storage();
        Path file = write(storage, "2026/10/a.zip");
        when(repository.findPurgeablePostIds(any(), anyInt())).thenReturn(List.of());
        when(repository.findPurgeableBlogIds(any(), anyInt())).thenReturn(List.of(7L));
        when(repository.findExportFiles(List.of(7L))).thenReturn(List.of("2026/10/a.zip", "2026/10/missing.zip"));
        when(repository.purgeBlogs(List.of(7L))).thenAnswer(invocation -> {
            assertThat(Files.exists(file)).as("커밋 전에는 남아 있다").isTrue();
            return 1L;
        });

        TrashPurgeJob.Result result = job(storage, new NoOpTransactionManager()).purge();

        assertThat(result.blogs()).isEqualTo(1);
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    void rolledBackPurgeKeepsTheFiles() throws IOException {
        ExportStorage storage = storage();
        Path file = write(storage, "2026/10/b.zip");
        when(repository.findPurgeablePostIds(any(), anyInt())).thenReturn(List.of());
        when(repository.findPurgeableBlogIds(any(), anyInt())).thenReturn(List.of(8L));
        when(repository.findExportFiles(List.of(8L))).thenReturn(List.of("2026/10/b.zip"));
        when(repository.purgeBlogs(List.of(8L))).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> job(storage, new NoOpTransactionManager()).purge())
                .isInstanceOf(IllegalStateException.class);

        assertThat(Files.exists(file)).isTrue();
    }

    private TrashPurgeJob job(ExportStorage storage, NoOpTransactionManager tx) {
        return new TrashPurgeJob(repository, new TransactionTemplate(tx),
                new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 500), new MutableClock(NOW), null, storage);
    }

    private ExportStorage storage() {
        return new ExportStorage(new ExportProperties(exportDir.toString(), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofHours(1)));
    }

    private Path write(ExportStorage storage, String relative) throws IOException {
        Path file = storage.baseDir().resolve(relative);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "PK");
    }

    /** 자원 없이 동기화만 하는 트랜잭션 관리자. */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 자원 없음
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // 자원 없음
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // 자원 없음
        }
    }
}
