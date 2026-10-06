package net.java21.blog.backend.media.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.media.MediaProperties;
import net.java21.blog.backend.media.TestImages;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.media.repository.MediaQueryRepository;
import net.java21.blog.backend.media.storage.LocalMediaStorage;
import net.java21.blog.backend.media.thumbnail.ThumbnailLocks;
import net.java21.blog.backend.media.thumbnail.ThumbnailService;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이미지 정리(T206, FR-072·073, AS3, quickstart #12): {@code created_at < now - temp-ttl}인 TEMP와 ORPHANED의 원본 파일·썸네일
 * 디렉터리·행을 지우고 건수를 로그에 남긴다. 아직 만료되지 않은 TEMP와 ATTACHED는 그대로. 파일은 {@code @TempDir}.
 */
@JpaRepositoryTest
@ExtendWith(OutputCaptureExtension.class)
@Import(MediaCleanupJobTest.Config.class)
class MediaCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-10-06T05:00:00Z");

    @TempDir
    static Path root;

    @TestConfiguration(proxyBeanMethods = false)
    @Import(MediaQueryRepository.class)
    static class Config {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        /** 배치 크기 2: 여러 번에 나눠 처리하는지 확인한다. */
        @Bean
        MediaCleanupJob mediaCleanupJob(MediaQueryRepository repository, TransactionTemplate transactionTemplate,
                MutableClock clock) {
            MediaProperties properties = TestImages.properties(root);
            LocalMediaStorage storage = new LocalMediaStorage(properties);
            return new MediaCleanupJob(repository, storage,
                    new ThumbnailService(properties, storage, new ThumbnailLocks()), transactionTemplate, properties,
                    new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 2), clock);
        }
    }

    @Autowired
    private MediaCleanupJob job;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private User owner;
    private int seq;

    @BeforeEach
    void setUp() {
        owner = new User("o@example.com", "o".repeat(64), "$2a$hash", "주인", null, null, "2026-10-06", NOW);
        em.persist(owner);
    }

    @Test
    void deletesExpiredTempAndOrphanedFilesThumbnailsAndRows(CapturedOutput output) throws Exception {
        Media expired1 = media(MediaStatus.TEMP, NOW.minus(Duration.ofHours(25)));
        Media expired2 = media(MediaStatus.TEMP, NOW.minus(Duration.ofHours(24)).minusSeconds(1));
        Media fresh = media(MediaStatus.TEMP, NOW.minus(Duration.ofHours(23)));
        Media orphan = media(MediaStatus.ORPHANED, NOW.minus(Duration.ofDays(100)));
        Media attached = media(MediaStatus.ATTACHED, NOW.minus(Duration.ofDays(100)));
        Path orphanThumbs = Files.createDirectories(root.resolve("thumb").resolve(orphan.getMediaKey()));
        Files.write(orphanThumbs.resolve("300x200-cover.png"), new byte[] {1});

        MediaCleanupJob.Result result = job.cleanup();

        assertThat(result).isEqualTo(new MediaCleanupJob.Result(2, 1));
        assertThat(ids()).containsExactlyInAnyOrder(fresh.getId(), attached.getId());
        assertThat(file(expired1)).doesNotExist();
        assertThat(file(expired2)).doesNotExist();
        assertThat(file(orphan)).doesNotExist();
        assertThat(orphanThumbs).doesNotExist();
        assertThat(file(fresh)).exists();
        assertThat(file(attached)).exists();
        assertThat(output).contains("Media cleanup finished: temp=2, orphaned=1");

        assertThat(job.cleanup()).isEqualTo(new MediaCleanupJob.Result(0, 0));
    }

    @Test
    void missingFileDoesNotStopTheCleanup() throws Exception {
        Media orphan = media(MediaStatus.ORPHANED, NOW);
        Files.delete(file(orphan));

        job.run();

        assertThat(ids()).isEmpty();
    }

    private List<Long> ids() {
        return jdbc.queryForList("SELECT id FROM media", Long.class);
    }

    private Path file(Media media) {
        return root.resolve(media.getStatus() == MediaStatus.TEMP ? "temp" : "upload").resolve(media.getStoredPath());
    }

    private Media media(MediaStatus status, Instant createdAt) throws Exception {
        String key = ("c" + (++seq) + "zzzzzzzzzzzzzzzzzzzzzzzz").substring(0, 22);
        String path = status == MediaStatus.TEMP ? key + ".png" : "2026/10/" + key + ".png";
        Path file = root.resolve(status == MediaStatus.TEMP ? "temp" : "upload").resolve(path);
        Files.createDirectories(file.getParent());
        Files.write(file, TestImages.png(2, 2));
        Media media = new Media(owner, key, MediaPurpose.POST, key + ".png", path, "image/png", 10, 2, 2);
        TestEntities.with(media, "status", status);
        em.persist(media);
        em.flush();
        jdbc.update("UPDATE media SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), media.getId());
        return media;
    }
}
