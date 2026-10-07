package net.java21.blog.backend.trackback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import net.java21.blog.backend.trackback.repository.TrackbackPingLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** 기동 복구(005 T091, research M15): 5분 넘은 PENDING 중 발행된 글의 것만 다시 보낸다. */
@JpaRepositoryTest
@Import(PendingPingRecoveryTest.Config.class)
class PendingPingRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

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
    private TrackbackPingLogRepository repository;
    @Autowired
    private MutableClock clock;

    private TrackbackDispatchListener dispatcher;
    private PendingPingRecovery recovery;
    private JpaFixtures fx;
    private Blog blog;

    @BeforeEach
    void setUp() {
        clock.set(NOW.minus(Duration.ofMinutes(10)));
        dispatcher = mock(TrackbackDispatchListener.class);
        recovery = new PendingPingRecovery(repository, dispatcher, TrackbackProperties.defaults(), clock);
        fx = new JpaFixtures(em);
        blog = fx.blog(fx.user("recover"), "recover");
    }

    private TrackbackPingLog pending(Post post, String url) {
        TrackbackPingLog log = new TrackbackPingLog(post, url);
        em.persist(log);
        return log;
    }

    @Test
    void resendsOldPendingOfPublishedPostsGroupedByPost() {
        Post published = fx.published(blog, "발행", null, 0);
        Post scheduled = fx.scheduled(blog, "예약", PostVisibility.PUBLIC, NOW.plusSeconds(3600));
        TrackbackPingLog a = pending(published, "https://ext.example/a");
        TrackbackPingLog b = pending(published, "https://ext.example/b");
        TrackbackPingLog done = pending(published, "https://ext.example/done");
        done.succeed(NOW.minusSeconds(500));
        pending(scheduled, "https://ext.example/scheduled");
        clock.set(NOW.minus(Duration.ofMinutes(2)));
        pending(published, "https://ext.example/recent");
        fx.flushAndClear();
        clock.set(NOW);

        assertThat(recovery.recover()).isEqualTo(1);

        ArgumentCaptor<TrackbackSendRequested> event = ArgumentCaptor.forClass(TrackbackSendRequested.class);
        verify(dispatcher).submit(event.capture());
        assertThat(event.getValue().postId()).isEqualTo(published.getId());
        assertThat(event.getValue().logIds()).containsExactly(a.getId(), b.getId());
    }

    @Test
    void nothingToRecoverAndFailuresDoNotStopStartup() {
        clock.set(NOW);
        assertThat(recovery.recover()).isZero();
        verify(dispatcher, never()).submit(any());

        Post published = fx.published(blog, "발행", null, 0);
        clock.set(NOW.minus(Duration.ofMinutes(10)));
        pending(published, "https://ext.example/a");
        fx.flushAndClear();
        clock.set(NOW);
        doThrow(new IllegalStateException("boom")).when(dispatcher).submit(any());
        recovery.onReady();
        verify(dispatcher).submit(any());

        PendingPingRecovery broken = new PendingPingRecovery(mock(TrackbackPingLogRepository.class, i -> {
            throw new IllegalStateException("db down");
        }), dispatcher, TrackbackProperties.defaults(), clock);
        broken.onReady();
    }
}
