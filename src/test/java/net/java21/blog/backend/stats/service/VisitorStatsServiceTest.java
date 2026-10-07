package net.java21.blog.backend.stats.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.stats.StatsProperties;
import net.java21.blog.backend.stats.domain.BlogDailyVisit;
import net.java21.blog.backend.stats.dto.VisitStatsResponse;
import net.java21.blog.backend.stats.dto.VisitorCountsResponse;
import net.java21.blog.backend.stats.repository.BlogVisitRepository;
import net.java21.blog.backend.stats.repository.StatsQueryRepository;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 방문자 수·통계(T057, 004 FR-067, 006 FR-100). */
@ExtendWith(MockitoExtension.class)
class VisitorStatsServiceTest {

    /** 한국 2026-10-08 00:30 */
    private static final Instant NOW = Instant.parse("2026-10-07T15:30:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 8);

    @Mock
    private BlogAccess blogAccess;
    @Mock
    private BlogVisitRepository visitRepository;
    @Mock
    private StatsQueryRepository statsRepository;

    private VisitorStatsService service;
    private Blog blog;

    @BeforeEach
    void setUp() {
        service = new VisitorStatsService(blogAccess, visitRepository, statsRepository,
                new BlogCalendar(StatsProperties.defaults(), new MutableClock(NOW)));
        blog = TestEntities.with(TestEntities.blog(10L, TestEntities.user(1L), "marco"), "totalVisitors", 1520L);
    }

    @Test
    void countsTodayYesterdayAndTotal() {
        when(visitRepository.findRange(10L, TODAY.minusDays(1), TODAY))
                .thenReturn(List.of(visit(TODAY.minusDays(1), 30), visit(TODAY, 12)));
        assertThat(service.counts(blog)).isEqualTo(new VisitorCountsResponse(12, 30, 1520));

        when(visitRepository.findRange(10L, TODAY.minusDays(1), TODAY)).thenReturn(List.of());
        assertThat(service.counts(blog)).isEqualTo(new VisitorCountsResponse(0, 0, 1520));
    }

    @Test
    void statsFillEmptyDaysAscendingWithTopPosts() {
        when(blogAccess.requireOwnedActiveBlog("marco", 1L)).thenReturn(blog);
        when(visitRepository.findRange(10L, TODAY.minusDays(2), TODAY))
                .thenReturn(List.of(visit(TODAY.minusDays(2), 4), visit(TODAY, 2)));
        List<VisitStatsResponse.TopPost> top = List.of(new VisitStatsResponse.TopPost(5L, "인기 글", 99));
        when(statsRepository.findTopPosts(10L, 10)).thenReturn(top);

        VisitStatsResponse stats = service.stats(1L, "marco", 3);

        assertThat(stats.daily()).containsExactly(new VisitStatsResponse.Daily(TODAY.minusDays(2), 4),
                new VisitStatsResponse.Daily(TODAY.minusDays(1), 0), new VisitStatsResponse.Daily(TODAY, 2));
        assertThat(stats.visitors()).isEqualTo(new VisitorCountsResponse(2, 0, 1520));
        assertThat(stats.topPosts()).isEqualTo(top);
    }

    @Test
    void defaultIsThirtyDaysAndOneDayStillKnowsYesterday() {
        when(blogAccess.requireOwnedActiveBlog("marco", 1L)).thenReturn(blog);
        when(visitRepository.findRange(10L, TODAY.minusDays(29), TODAY)).thenReturn(List.of());
        assertThat(service.stats(1L, "marco", null).daily()).hasSize(30).first()
                .isEqualTo(new VisitStatsResponse.Daily(TODAY.minusDays(29), 0));

        when(visitRepository.findRange(10L, TODAY.minusDays(1), TODAY))
                .thenReturn(List.of(visit(TODAY.minusDays(1), 7)));
        VisitStatsResponse one = service.stats(1L, "marco", 1);
        assertThat(one.daily()).containsExactly(new VisitStatsResponse.Daily(TODAY, 0));
        assertThat(one.visitors().yesterday()).isEqualTo(7);
    }

    @Test
    void daysOutOfRangeIs400AndOthersAreForbidden() {
        assertCode(() -> service.stats(1L, "marco", 0), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.stats(1L, "marco", 31), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(blogAccess);
        when(blogAccess.requireOwnedActiveBlog("marco", 2L)).thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "x"));
        assertCode(() -> service.stats(2L, "marco", 7), ErrorCode.FORBIDDEN);
    }

    private BlogDailyVisit visit(LocalDate date, int visitors) {
        return new BlogDailyVisit(blog, date, visitors);
    }
}
