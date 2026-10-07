package net.java21.blog.backend.stats;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * 블로그 날짜 기준(004 research B8·B12): 방문 날짜·월별 보관함·월별 글 목록은 회원 시간대가 아니라 서비스 기준 시간대
 * {@code blog.stats.time-zone}(기본 Asia/Seoul)로 정한다.
 */
@Component
public class BlogCalendar {

    private final ZoneId zone;
    private final Clock clock;

    public BlogCalendar(StatsProperties properties, Clock clock) {
        this.zone = properties.zone();
        this.clock = clock;
    }

    /** 발행 시각 범위 [시작, 끝): 그 달 1일 00:00(포함) ~ 다음 달 1일 00:00(제외), 기준 시간대. */
    public record Range(Instant from, Instant to) {
    }

    public ZoneId zone() {
        return zone;
    }

    public Range monthRange(YearMonth month) {
        return new Range(month.atDay(1).atStartOfDay(zone).toInstant(),
                month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
    }

    public Range dayRange(LocalDate date) {
        return new Range(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant());
    }

    public YearMonth monthOf(Instant instant) {
        return YearMonth.from(instant.atZone(zone));
    }

    public LocalDate dateOf(Instant instant) {
        return instant.atZone(zone).toLocalDate();
    }

    /** 기준 시간대의 오늘. */
    public LocalDate today() {
        return dateOf(clock.instant());
    }
}
