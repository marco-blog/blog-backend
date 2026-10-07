package net.java21.blog.backend.external.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.repository.ExternalPostDailyClickRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.support.ExternalTestKit;
import net.java21.blog.backend.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 007 T047: 클릭 — 노출 글이면 링크, 같은 방문자·같은 글은 30분에 한 번(Caffeine {@code Ticker}), 회원·쿠키·IP 키는 서로 다름, 세면
 * {@code click_count + 1}과 그날(UTC) 일별 클릭 upsert, 노출이 아니면 404 (FR-124, research E14).
 */
@ExtendWith(MockitoExtension.class)
class ExternalClickServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T23:59:00Z");

    @Mock
    private ExternalPortalQueryRepository queries;
    @Mock
    private ExternalPostRepository postRepository;
    @Mock
    private ExternalPostDailyClickRepository dailyClicks;

    private final AtomicLong nanos = new AtomicLong();
    private MutableClock clock;
    private ExternalClickService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = new ExternalClickService(queries, postRepository, dailyClicks, ExternalTestKit.properties(), clock,
                nanos::get);
    }

    @Test
    void countsOncePerVisitorWithinWindow() {
        when(queries.findVisibleLink(7L, NOW)).thenReturn(Optional.of("https://a.example/1"));

        assertThat(service.visit(7L, "v:abc")).isEqualTo("https://a.example/1");
        service.visit(7L, "v:abc");
        service.visit(7L, "u:1");
        service.visit(7L, "ip:hash");

        verify(postRepository, times(3)).incrementClick(7L);
        verify(dailyClicks, times(3)).upsertClick(7L, LocalDate.parse("2026-10-06"), NOW);

        nanos.addAndGet(Duration.ofMinutes(30).toNanos() + 1);
        service.visit(7L, "v:abc");
        verify(postRepository, times(4)).incrementClick(7L);
    }

    @Test
    void utcDayOfClick() {
        clock.advance(Duration.ofMinutes(2));
        Instant later = clock.instant();
        when(queries.findVisibleLink(7L, later)).thenReturn(Optional.of("https://a.example/1"));

        service.visit(7L, "v:x");

        verify(dailyClicks).upsertClick(7L, LocalDate.parse("2026-10-07"), later);
    }

    @Test
    void invisiblePostIs404AndNotCounted() {
        when(queries.findVisibleLink(anyLong(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.visit(9L, "v:x")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_POST_NOT_FOUND));
        verify(postRepository, never()).incrementClick(anyLong());
    }

    @Test
    void withoutAnyKeyItRedirectsWithoutCounting() {
        when(queries.findVisibleLink(7L, NOW)).thenReturn(Optional.of("https://a.example/1"));

        assertThat(service.visit(7L, null)).isEqualTo("https://a.example/1");
        verify(postRepository, never()).incrementClick(anyLong());
    }
}
