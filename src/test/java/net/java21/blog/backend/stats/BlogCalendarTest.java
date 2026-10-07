package net.java21.blog.backend.stats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** 기준 시간대 날짜 계산(T065, research B8·B12). */
class BlogCalendarTest {

    private final BlogCalendar calendar = new BlogCalendar(StatsProperties.defaults(),
            Clock.fixed(Instant.parse("2026-10-07T15:00:00Z"), ZoneOffset.UTC));

    @Test
    void monthAndDayRangesInSeoul() {
        assertThat(calendar.zone()).isEqualTo(ZoneId.of("Asia/Seoul"));
        BlogCalendar.Range october = calendar.monthRange(YearMonth.of(2026, 10));
        assertThat(october.from()).isEqualTo(Instant.parse("2026-09-30T15:00:00Z"));
        assertThat(october.to()).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        BlogCalendar.Range december = calendar.monthRange(YearMonth.of(2026, 12));
        assertThat(december.to()).isEqualTo(Instant.parse("2026-12-31T15:00:00Z"));
        BlogCalendar.Range day = calendar.dayRange(LocalDate.of(2026, 10, 8));
        assertThat(day.from()).isEqualTo(Instant.parse("2026-10-07T15:00:00Z"));
        assertThat(day.to()).isEqualTo(Instant.parse("2026-10-08T15:00:00Z"));
    }

    @Test
    void instantToLocalDateAndMonth() {
        assertThat(calendar.today()).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(calendar.dateOf(Instant.parse("2026-10-07T14:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(calendar.monthOf(Instant.parse("2026-09-30T15:00:00Z"))).isEqualTo(YearMonth.of(2026, 10));
    }
}
