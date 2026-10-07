package net.java21.blog.backend.export.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.export.domain.BlogExport;
import net.java21.blog.backend.export.domain.ExportStatus;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 백업 행 조회·조건부 UPDATE(T099, research B14): 최근 24시간 FAILED가 아닌 행, 최근 10건, 가장 오래된 PENDING,
 * PENDING → RUNNING(1행·0행), 만료된 READY, 오래된 RUNNING. 각 쿼리 1회.
 */
@JpaRepositoryTest
@Import({BlogExportQueryRepository.class, BlogExportRepositoryTest.Config.class})
class BlogExportRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private EntityManager em;
    @Autowired
    private BlogExportQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private MutableClock clock;

    private JpaFixtures fx;
    private User owner;
    private Blog blog;
    private Blog other;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        fx = new JpaFixtures(em);
        owner = fx.user("백업 주인");
        blog = fx.blog(owner, "backup");
        other = fx.blog(fx.user("다른 주인"), "otherbackup");
    }

    @Test
    void activeSinceIgnoresFailedAndOlderAndOtherBlogs() {
        BlogExport old = persist(blog);
        old.markReady("2026/10/a.zip", 10, NOW, Duration.ofDays(7));
        clock.advance(Duration.ofHours(25));
        BlogExport failed = persist(blog);
        failed.markFailed("IO_ERROR");
        persist(other);
        fx.flushAndClear();
        Instant since = clock.instant().minus(Duration.ofHours(24));

        queryCounter.reset();
        assertThat(repository.existsActiveSince(blog.getId(), since)).isFalse();
        assertThat(queryCounter.count()).isEqualTo(1);

        persist(blog);
        fx.flushAndClear();
        assertThat(repository.existsActiveSince(blog.getId(), since)).isTrue();
    }

    @Test
    void recentListsTenNewestOfThisBlog() {
        for (int i = 0; i < 12; i++) {
            persist(blog);
            clock.advance(Duration.ofMinutes(1));
        }
        persist(other);
        fx.flushAndClear();

        queryCounter.reset();
        List<BlogExport> recent = repository.findRecent(blog.getId());
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(recent).hasSize(10);
        assertThat(recent.getFirst().getCreatedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(11)));
        assertThat(recent).allSatisfy(e -> assertThat(e.getBlog().getId()).isEqualTo(blog.getId()));
    }

    @Test
    void oldestPendingIsClaimedOnce() {
        assertThat(repository.findOldestPendingId()).isNull();
        BlogExport first = persist(blog);
        clock.advance(Duration.ofMinutes(1));
        BlogExport second = persist(other);
        fx.flushAndClear();

        queryCounter.reset();
        assertThat(repository.findOldestPendingId()).isEqualTo(first.getId());
        assertThat(repository.claim(first.getId(), "2026/10/x.zip", clock.instant())).isEqualTo(1);
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(repository.claim(first.getId(), "2026/10/y.zip", clock.instant())).as("이미 가져감").isZero();
        assertThat(repository.findOldestPendingId()).isEqualTo(second.getId());
        em.clear();

        BlogExport claimed = em.find(BlogExport.class, first.getId());
        assertThat(claimed.getStatus()).isEqualTo(ExportStatus.RUNNING);
        assertThat(claimed.getFilePath()).isEqualTo("2026/10/x.zip");
        assertThat(claimed.getUpdatedAt()).isEqualTo(clock.instant());
    }

    @Test
    void expiredReadyAndStaleRunning() {
        BlogExport expired = persist(blog);
        expired.markReady("2026/10/old.zip", 10, NOW, Duration.ofDays(7));
        BlogExport fresh = persist(blog);
        fresh.markReady("2026/10/new.zip", 10, NOW.plus(Duration.ofDays(2)), Duration.ofDays(7));
        BlogExport running = persist(other);
        fx.flushAndClear();
        repository.claim(running.getId(), "2026/10/run.zip", NOW);
        em.clear();

        Instant later = NOW.plus(Duration.ofDays(8));
        queryCounter.reset();
        assertThat(repository.findExpiredReady(later, 10)).extracting(BlogExport::getId)
                .containsExactly(expired.getId());
        assertThat(repository.findStaleRunning(NOW.plus(Duration.ofHours(1)))).extracting(BlogExport::getId)
                .containsExactly(running.getId());
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(repository.findStaleRunning(NOW)).isEmpty();
        assertThat(repository.findExpiredReady(later, 1)).hasSize(1);
    }

    private BlogExport persist(Blog target) {
        BlogExport export = new BlogExport(target, target.getUser());
        em.persist(export);
        em.flush();
        return export;
    }
}
