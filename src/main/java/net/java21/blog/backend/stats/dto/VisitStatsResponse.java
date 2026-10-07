package net.java21.blog.backend.stats.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 블로그 통계(004 contracts/api.md {@code GET /blogs/{handle}/manage/stats}): 방문자 수, 최근 {@code days}일 일별 방문자(오늘 포함,
 * 날짜 오름차순, 기록 없는 날 0), 조회수 상위 10편.
 */
public record VisitStatsResponse(VisitorCountsResponse visitors, List<Daily> daily, List<TopPost> topPosts) {

    public record Daily(LocalDate date, long visitors) {
    }

    public record TopPost(Long id, String title, long viewCount) {
    }
}
