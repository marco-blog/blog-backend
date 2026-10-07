package net.java21.blog.backend.external.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalBlogVerification;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostDailyClick;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.release.ExternalPostPurger;
import net.java21.blog.backend.external.repository.ExternalBlogVerificationRepository;
import net.java21.blog.backend.external.repository.ExternalPostDailyClickRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.external.thumbnail.ExternalThumbnailService;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.portal.repository.PortalExclusionRepository;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 007 T075: 정리 작업(research E15). 만료 7일 지난 인증 코드, 해제된 등록에서 내린 지 30일 지난 글(남긴 ACTIVE 글은 기한 없음), 90일
 * 지난 일별 클릭, 월요일 실행분만 DB에 없는 썸네일 파일. H2, 임시 디렉터리.
 */
@JpaRepositoryTest
class ExternalCleanupJobTest {

    /** 2026-10-12는 월요일. */
    private static final Instant MONDAY = Instant.parse("2026-10-12T05:30:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogVerificationRepository verificationRepository;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private ExternalPostDailyClickRepository clickRepository;
    @Autowired
    private PortalExclusionRepository exclusionRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @TempDir
    Path thumbDir;

    private MutableClock clock;
    private ExternalCleanupJob job;
    private ExternalFixtures x;
    private JpaFixtures f;
    private Topic topic;
    private User member;

    @BeforeEach
    void setUp() {
        f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        topic = f.topic(f.topic(null, "knowledge", 1), "it-internet", 1);
        member = f.user("member");
        clock = new MutableClock(MONDAY);
        ExternalThumbnailService thumbnails = mock(ExternalThumbnailService.class);
        when(thumbnails.root()).thenReturn(thumbDir);
        job = new ExternalCleanupJob(verificationRepository, postRepository, clickRepository,
                new ExternalPostPurger(postRepository, exclusionRepository, mock(ApplicationEventPublisher.class)),
                thumbnails, ExternalFeedProperties.defaults(), new TransactionTemplate(transactionManager), clock);
    }

    private void touch(ExternalPost post, Instant updatedAt) {
        em.flush();
        em.createNativeQuery("UPDATE external_posts SET updated_at = :at WHERE id = :id")
                .setParameter("at", updatedAt).setParameter("id", post.getId()).executeUpdate();
    }

    @Test
    void deletesOnlyWhatIsPastRetention() {
        ExternalBlogVerification old = new ExternalBlogVerification(member, "h1", "java21-verify-AAAAAAAAAAAA",
                MONDAY.minus(Duration.ofDays(8)));
        ExternalBlogVerification recent = new ExternalBlogVerification(member, "h2", "java21-verify-BBBBBBBBBBBB",
                MONDAY.minus(Duration.ofDays(6)));
        em.persist(old);
        em.persist(recent);

        ExternalBlog released = x.blog(member, topic, ExternalBlogStatus.RELEASED);
        ExternalPost kept = x.post(released, "kept", topic, null);
        ExternalPost expired = x.removed(released, "expired", topic, RemovedReason.MEMBER_WITHDRAWN);
        ExternalPost young = x.removed(released, "young", topic, RemovedReason.REPORT);
        ExternalBlog active = x.blog(f.user("other"), topic, ExternalBlogStatus.ACTIVE);
        ExternalPost activeRemoved = x.removed(active, "active-removed", topic, RemovedReason.ADMIN);
        em.persist(new PortalExclusion(expired, "x", member));
        em.persist(new ExternalPostDailyClick(kept, LocalDate.of(2026, 7, 13), 1));
        em.persist(new ExternalPostDailyClick(kept, LocalDate.of(2026, 7, 15), 1));
        touch(kept, MONDAY.minus(Duration.ofDays(400)));
        touch(expired, MONDAY.minus(Duration.ofDays(31)));
        touch(young, MONDAY.minus(Duration.ofDays(29)));
        touch(activeRemoved, MONDAY.minus(Duration.ofDays(400)));
        em.flush();
        em.clear();

        ExternalCleanupJob.Summary summary = job.runOnce();

        assertThat(summary.verifications()).isEqualTo(1);
        assertThat(summary.posts()).isEqualTo(1);
        assertThat(summary.clicks()).isEqualTo(1);
        assertThat(em.find(ExternalBlogVerification.class, old.getId())).isNull();
        assertThat(em.find(ExternalBlogVerification.class, recent.getId())).isNotNull();
        assertThat(postRepository.findById(expired.getId())).isEmpty();
        assertThat(postRepository.findById(kept.getId())).as("남긴 글은 기한 없음").isPresent();
        assertThat(postRepository.findById(young.getId())).isPresent();
        assertThat(postRepository.findById(activeRemoved.getId())).as("해제되지 않은 등록의 글").isPresent();
        assertThat(em.createQuery("select c.id.clickDate from ExternalPostDailyClick c", LocalDate.class)
                .getResultList()).containsExactly(LocalDate.of(2026, 7, 15));
    }

    @Test
    void mondayRunRemovesOrphanThumbnailFilesOnly() throws IOException {
        ExternalBlog blog = x.blog(member, topic, ExternalBlogStatus.ACTIVE);
        ExternalPost post = x.post(blog, "with thumb", topic, null);
        post.attachThumbnail("KeepKeepKeepKeepKeep12");
        em.flush();
        Path keep = write("KeepKeepKeepKeepKeep12.jpg");
        Path orphan = write("GoneGoneGoneGoneGone12.jpg");
        Path orphanPng = write("GoneGoneGoneGoneGone12.png");
        Path stranger = write("README.txt");

        assertThat(job.runOnce().orphanFiles()).isEqualTo(2);
        assertThat(keep).exists();
        assertThat(orphan).doesNotExist();
        assertThat(orphanPng).doesNotExist();
        assertThat(stranger).exists();

        Path later = write("LateLateLateLateLate12.jpg");
        clock.set(MONDAY.plus(Duration.ofDays(1)));
        assertThat(job.runOnce().orphanFiles()).as("월요일이 아니면 파일은 보지 않음").isZero();
        assertThat(later).exists();
    }

    @Test
    void missingDirectoryIsFine() throws IOException {
        Files.delete(thumbDir);
        assertThat(job.runOnce().orphanFiles()).isZero();
        assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    private Path write(String name) throws IOException {
        Path dir = thumbDir.resolve(name.substring(0, 2));
        Files.createDirectories(dir);
        return Files.writeString(dir.resolve(name), "x");
    }
}
