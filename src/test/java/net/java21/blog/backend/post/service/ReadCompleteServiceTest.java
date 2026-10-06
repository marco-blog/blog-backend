package net.java21.blog.backend.post.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostDailyStatsRepository;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 끝까지 읽음(003 T029, FR-086, research P4): 상세를 볼 수 있는 글만, 같은 방문자 30분 안 재요청은 세지 않음. */
@ExtendWith(MockitoExtension.class)
class ReadCompleteServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-06");

    @Mock
    private PostRepository postRepository;
    @Mock
    private PostDailyStatsRepository dailyStats;

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final MutableClock clock = new MutableClock(NOW);
    private final PostsProperties properties =
            new PostsProperties(Duration.ofMinutes(30), 1000, "visitor_id", Duration.ofDays(365));
    private ReadCompleteService service;
    private Post post;

    @BeforeEach
    void setUp() {
        service = new ReadCompleteService(postRepository, dailyStats, properties, clock, ticker);
        post = TestEntities.post(100L, TestEntities.blog(10L, TestEntities.user(1L), "marco"), "t");
    }

    @Test
    void sameVisitorWithin30MinutesCountsOnceThenAgainAfter() {
        publish(PostVisibility.PUBLIC);

        assertThat(service.record(100L, null, "v:abc")).isTrue();
        nanos.addAndGet(Duration.ofMinutes(29).toNanos());
        assertThat(service.record(100L, null, "v:abc")).isFalse();
        verify(dailyStats, times(1)).upsertReadComplete(100L, TODAY, NOW);

        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        assertThat(service.record(100L, null, "v:abc")).isTrue();
        verify(dailyStats, times(2)).upsertReadComplete(100L, TODAY, NOW);
    }

    @Test
    void differentVisitorsCountSeparately() {
        publish(PostVisibility.PUBLIC);

        assertThat(service.record(100L, null, "v:abc")).isTrue();
        assertThat(service.record(100L, null, "v:def")).isTrue();
        assertThat(service.record(100L, 7L, "u:7")).isTrue();
        verify(dailyStats, times(3)).upsertReadComplete(100L, TODAY, NOW);
    }

    @Test
    void postNotVisibleInDetailIsNotFoundAndNotCounted() {
        publish(PostVisibility.PRIVATE);
        when(postRepository.findWithBlogAndOwner(404L)).thenReturn(Optional.empty());

        assertCode(() -> service.record(100L, null, "v:abc"), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.record(100L, 2L, "u:2"), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.record(404L, null, "v:abc"), ErrorCode.POST_NOT_FOUND);
        verify(dailyStats, never()).upsertReadComplete(anyLong(), any(), any());
        assertThat(service.record(100L, 1L, "u:1")).as("주인은 자기 비공개 글 상세를 본다").isTrue();
    }

    @Test
    void productionConstructorUsesSystemTicker() {
        publish(PostVisibility.PUBLIC);
        ReadCompleteService real = new ReadCompleteService(postRepository, dailyStats, properties, clock);
        assertThat(real.record(100L, null, "v:x")).isTrue();
        assertThat(real.record(100L, null, "v:x")).isFalse();
    }

    private void publish(PostVisibility visibility) {
        post.publish("t", "b", "<p>b</p>", "b", "b", null, visibility, true, NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
    }
}
