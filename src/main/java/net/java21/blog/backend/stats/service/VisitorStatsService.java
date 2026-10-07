package net.java21.blog.backend.stats.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.stats.domain.BlogDailyVisit;
import net.java21.blog.backend.stats.dto.VisitStatsResponse;
import net.java21.blog.backend.stats.dto.VisitorCountsResponse;
import net.java21.blog.backend.stats.repository.BlogVisitRepository;
import net.java21.blog.backend.stats.repository.StatsQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 방문자 수와 블로그 통계(004 FR-067, 006 FR-100). 날짜는 {@link BlogCalendar} 기준. */
@Service
public class VisitorStatsService {

    public static final int DEFAULT_DAYS = 30;
    public static final int MAX_DAYS = 30;
    static final int TOP_POSTS = 10;

    private final BlogAccess blogAccess;
    private final BlogVisitRepository visitRepository;
    private final StatsQueryRepository statsRepository;
    private final BlogCalendar calendar;

    public VisitorStatsService(BlogAccess blogAccess, BlogVisitRepository visitRepository,
            StatsQueryRepository statsRepository, BlogCalendar calendar) {
        this.blogAccess = blogAccess;
        this.visitRepository = visitRepository;
        this.statsRepository = statsRepository;
        this.calendar = calendar;
    }

    /** 오늘·어제·전체(사이드바·대시보드). 쿼리 1회(어제~오늘 행). 전체는 이미 읽은 블로그 행의 값. */
    @Transactional(readOnly = true)
    public VisitorCountsResponse counts(Blog blog) {
        LocalDate today = calendar.today();
        Map<LocalDate, Long> byDate = byDate(visitRepository.findRange(blog.getId(), today.minusDays(1), today));
        return new VisitorCountsResponse(byDate.getOrDefault(today, 0L), byDate.getOrDefault(today.minusDays(1), 0L),
                blog.getTotalVisitors());
    }

    /** 주인 통계 화면. {@code days}는 1~30(null이면 30). 쿼리 4회(블로그, 기간 행, 상위 글; 방문자 수는 기간 행에서 계산). */
    @Transactional(readOnly = true)
    public VisitStatsResponse stats(long userId, String handle, Integer days) {
        int span = days == null ? DEFAULT_DAYS : days;
        if (span < 1 || span > MAX_DAYS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "days out of range: " + days,
                    List.of(new FieldError("days", "INVALID", Map.of("min", 1, "max", MAX_DAYS))));
        }
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        LocalDate today = calendar.today();
        LocalDate from = today.minusDays(Math.max(span, 2) - 1L);
        Map<LocalDate, Long> byDate = byDate(visitRepository.findRange(blog.getId(), from, today));
        List<VisitStatsResponse.Daily> daily = new ArrayList<>();
        for (LocalDate d = today.minusDays(span - 1L); !d.isAfter(today); d = d.plusDays(1)) {
            daily.add(new VisitStatsResponse.Daily(d, byDate.getOrDefault(d, 0L)));
        }
        VisitorCountsResponse visitors = new VisitorCountsResponse(byDate.getOrDefault(today, 0L),
                byDate.getOrDefault(today.minusDays(1), 0L), blog.getTotalVisitors());
        return new VisitStatsResponse(visitors, daily, statsRepository.findTopPosts(blog.getId(), TOP_POSTS));
    }

    private static Map<LocalDate, Long> byDate(List<BlogDailyVisit> rows) {
        Map<LocalDate, Long> map = new HashMap<>();
        for (BlogDailyVisit row : rows) {
            map.put(row.getVisitDate(), (long) row.getVisitors());
        }
        return map;
    }
}
