package net.java21.blog.backend.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.dto.LatestSection;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.dto.PortalHomeResponse;
import net.java21.blog.backend.portal.repository.NewBlogRow;
import net.java21.blog.backend.portal.repository.PopularTagRow;
import net.java21.blog.backend.portal.repository.PortalCardQueryRepository;
import net.java21.blog.backend.portal.repository.PortalCardRow;
import net.java21.blog.backend.portal.repository.PortalSectionQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 포털 메인 묶음(003 T037, FR-080·085~087·090, research P5·P6): 영역 구성, 2편 제한, 커서, 캐시(TTL·0s·invalidateAll).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortalServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final PortalCriteria CRITERIA = new PortalCriteria(NOW, Duration.ofHours(24), 200);

    @Mock
    private PortalCriteriaFactory criteriaFactory;
    @Mock
    private PortalCardQueryRepository cards;
    @Mock
    private PortalSectionQueryRepository sections;
    @Mock
    private PopularityCalculator calculator;

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private PortalCache cache;
    private PortalService service;

    @BeforeEach
    void setUp() {
        cache = new PortalCache(PortalProperties.defaults(), ticker);
        service = newService(cache);
        when(criteriaFactory.now()).thenReturn(CRITERIA);
        when(calculator.calculate(any())).thenReturn(new PopularitySnapshot(List.of()));
        when(cards.findLatest(any(), any(), anyInt())).thenReturn(List.of());
        when(cards.findByIds(anyList(), any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(id -> card(id, 1)).toList();
        });
    }

    @Test
    void homeBundlesEverySectionWithPerBlogCapOnlyOnMainSections() {
        when(sections.findActiveCurationPostIds(CRITERIA, NOW, 5)).thenReturn(List.of(1L, 2L, 3L));
        List<PopularitySnapshot.Entry> entries = new ArrayList<>();
        for (long id = 100; id < 120; id++) {
            entries.add(new PopularitySnapshot.Entry(id, id < 105 ? 1L : id, null, NOW, 200 - id));
        }
        when(calculator.calculate(CRITERIA)).thenReturn(new PopularitySnapshot(entries));
        List<PortalCardRow> latest = new ArrayList<>();
        for (long id = 50; id > 20; id--) {
            latest.add(card(id, id > 45 ? 7 : id));
        }
        when(cards.findLatest(CRITERIA, null, PortalService.LATEST_WINDOW + 1)).thenReturn(latest);
        when(sections.findPopularTags(CRITERIA, NOW.minus(Duration.ofDays(7)), 20))
                .thenReturn(List.of(new PopularTagRow("spring", 3)));
        when(sections.findNewBlogs(CRITERIA, NOW.minus(Duration.ofDays(30)), 6))
                .thenReturn(List.of(new NewBlogRow("marco", "블로그", null, null, "마르코", "key0000000000000000000", NOW)));

        PortalHomeResponse home = service.home();

        assertThat(home.curations()).extracting(PortalCardResponse::id).containsExactly(1L, 2L, 3L);
        assertThat(home.popular()).hasSize(12).extracting(PortalCardResponse::id)
                .startsWith(100L, 101L, 105L, 106L).doesNotContain(102L, 103L, 104L);
        assertThat(home.latest().items()).hasSize(20).extracting(PortalCardResponse::id)
                .startsWith(50L, 49L, 45L).doesNotContain(48L, 47L, 46L);
        assertThat(home.latest().nextCursor()).isNotNull();
        assertThat(PortalCursor.decode(home.latest().nextCursor())).isEqualTo(
                new PortalCursor.Position(NOW.minusSeconds(28), 28L));
        assertThat(home.popularTags()).singleElement().satisfies(tag -> {
            assertThat(tag.name()).isEqualTo("spring");
            assertThat(tag.postCount()).isEqualTo(3);
        });
        assertThat(home.newBlogs()).singleElement().satisfies(blog -> {
            assertThat(blog.handle()).isEqualTo("marco");
            assertThat(blog.owner().profileImageUrl()).isEqualTo("/media/key0000000000000000000");
            assertThat(blog.coverImageUrl()).isNull();
        });
        assertThat(home.generatedAt()).isEqualTo(NOW);
    }

    @Test
    void emptySectionsAreEmptyListsAndNoNextCursor() {
        when(sections.findActiveCurationPostIds(any(), any(), anyInt())).thenReturn(List.of());

        PortalHomeResponse home = service.home();

        assertThat(home.curations()).isEmpty();
        assertThat(home.popular()).isEmpty();
        assertThat(home.latest().items()).isEmpty();
        assertThat(home.latest().nextCursor()).isNull();
        assertThat(home.popularTags()).isEmpty();
        assertThat(home.newBlogs()).isEmpty();
    }

    @Test
    void latestContinuesAfterTheCursorAndStopsWhenNothingRemains() {
        PortalCursor.Position after = new PortalCursor.Position(NOW.minusSeconds(100), 100L);
        when(cards.findLatest(CRITERIA, after, PortalService.LATEST_WINDOW + 1))
                .thenReturn(List.of(card(99, 1), card(98, 2)));

        LatestSection section = service.latest(PortalCursor.encode(after));

        assertThat(section.items()).extracting(PortalCardResponse::id).containsExactly(99L, 98L);
        assertThat(section.nextCursor()).isNull();
    }

    @Test
    void latestGivesACursorWhenTheWindowIsExhaustedByOneBlog() {
        List<PortalCardRow> rows = new ArrayList<>();
        for (long id = 1000; id > 1000 - PortalService.LATEST_WINDOW - 1; id--) {
            rows.add(card(id, 1));
        }
        when(cards.findLatest(CRITERIA, null, PortalService.LATEST_WINDOW + 1)).thenReturn(rows);

        LatestSection section = service.latest(null);

        assertThat(section.items()).hasSize(2);
        PortalCardRow lastExamined = rows.get(PortalService.LATEST_WINDOW - 1);
        assertThat(PortalCursor.decode(section.nextCursor()))
                .isEqualTo(new PortalCursor.Position(lastExamined.publishedAt(), lastExamined.id()));
    }

    @Test
    void secondCallWithinTtlIsCachedUntilExpiryOrInvalidation() {
        service.home();
        service.home();
        verify(cards, times(1)).findLatest(any(), isNull(), anyInt());
        verify(calculator, times(1)).calculate(any());

        nanos.addAndGet(Duration.ofMinutes(5).toNanos());
        service.home();
        verify(cards, times(2)).findLatest(any(), isNull(), anyInt());

        cache.invalidateAll();
        service.home();
        verify(cards, times(3)).findLatest(any(), isNull(), anyInt());

        service.latest(null);
        service.latest(null);
        verify(cards, times(4)).findLatest(any(), isNull(), anyInt());
        service.popularity();
        verify(calculator, times(3)).calculate(any());
    }

    @Test
    void zeroTtlComputesEveryTime() {
        PortalProperties d = PortalProperties.defaults();
        PortalCache disabled = new PortalCache(new PortalProperties(Duration.ZERO, d.cacheMaxSize(), d.scoreWeights(),
                d.newMemberDelay(), d.minContentLength(), d.topicAutoHideThreshold(), d.popularWindow(),
                d.topicCountWindow()));
        PortalService uncached = newService(disabled);

        uncached.home();
        uncached.home();
        uncached.latest(null);

        assertThat(disabled.enabled()).isFalse();
        verify(cards, times(3)).findLatest(any(), eq(null), anyInt());
    }

    private PortalService newService(PortalCache portalCache) {
        return new PortalService(portalCache, criteriaFactory, cards, sections, calculator,
                PortalProperties.defaults());
    }

    private static PortalCardRow card(long id, long blogId) {
        return new PortalCardRow(id, "t" + id, "s", null, null, blogId, "h" + blogId, "b", "n", null,
                NOW.minusSeconds(id), 0, 0);
    }
}
