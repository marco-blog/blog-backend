package net.java21.blog.backend.stats.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.stats.StatsProperties;
import net.java21.blog.backend.stats.repository.BlogVisitRepository;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 방문자 세기(T054, 004 FR-067, research B9). */
@ExtendWith(MockitoExtension.class)
class VisitServiceTest {

    private static final String BROWSER = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130.0";
    /** 2026-10-07 14:00 UTC = 2026-10-07 23:00 KST */
    private static final Instant NOW = Instant.parse("2026-10-07T14:00:00Z");

    @Mock
    private BlogAccess blogAccess;
    @Mock
    private BlogVisitRepository repository;

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final MutableClock clock = new MutableClock(NOW);
    private VisitService service;
    private Blog blog;

    @BeforeEach
    void setUp() {
        StatsProperties properties = StatsProperties.defaults();
        service = new VisitService(blogAccess, repository, new BlogCalendar(properties, clock), properties, clock,
                ticker);
        blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        lenient().when(blogAccess.requireVisibleBlog("marco")).thenReturn(blog);
    }

    @Test
    void sameVisitorCountsOncePerServiceDay() {
        assertThat(service.record("marco", null, "v:abc", BROWSER)).isTrue();
        assertThat(service.record("marco", null, "v:abc", BROWSER)).isFalse();
        assertThat(service.record("marco", 7L, "u:7", BROWSER)).isTrue();
        verify(repository, times(2)).upsertVisit(10L, LocalDate.of(2026, 10, 7), NOW);
        verify(repository, times(2)).incrementTotal(10L);

        // 1시간 뒤 = 한국 날짜가 바뀜(UTC 15:00): 다시 센다
        clock.advance(Duration.ofHours(1));
        assertThat(service.record("marco", null, "v:abc", BROWSER)).isTrue();
        verify(repository).upsertVisit(10L, LocalDate.of(2026, 10, 8), NOW.plusSeconds(3600));
    }

    @Test
    void cacheEntryExpiresAfterTwentyFiveHours() {
        assertThat(service.record("marco", null, "v:abc", BROWSER)).isTrue();
        nanos.addAndGet(Duration.ofHours(25).toNanos());
        clock.advance(Duration.ofHours(25));
        assertThat(service.record("marco", null, "v:abc", BROWSER)).isTrue();
        verify(repository, times(2)).incrementTotal(10L);
    }

    @Test
    void ownerAndMissingVisitorKeyAreNotCounted() {
        assertThat(service.record("marco", 1L, "u:1", BROWSER)).isFalse();
        assertThat(service.record("marco", null, null, BROWSER)).isFalse();
        verify(repository, never()).upsertVisit(anyLong(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "Mozilla/5.0 (compatible; bingbot/2.0)", "facebookexternalhit/1.1", "Slackbot-LinkExpanding 1.0",
            "Mozilla/5.0 (compatible; YandexSpider)", "Twitterbot/1.0 Preview"})
    void botsAreNotCounted(String userAgent) {
        assertThat(service.record("marco", null, "v:abc", userAgent)).isFalse();
        verify(repository, never()).incrementTotal(anyLong());
    }

    @Test
    void nullUserAgentIsBotButHeadlessChromeCounts() {
        assertThat(service.record("marco", null, "v:abc", null)).isFalse();
        assertThat(service.record("marco", null, "v:abc",
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/130.0 Safari/537.36"))
                .isTrue();
    }

    @Test
    void invisibleBlogIs404() {
        when(blogAccess.requireVisibleBlog("ghost")).thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "x"));
        assertCode(() -> service.record("ghost", null, "v:abc", BROWSER), ErrorCode.BLOG_NOT_FOUND);
    }
}
