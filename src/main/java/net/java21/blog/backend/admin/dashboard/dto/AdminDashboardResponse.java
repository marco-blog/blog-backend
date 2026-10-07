package net.java21.blog.backend.admin.dashboard.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import net.java21.blog.backend.admin.dashboard.DashboardCounts;

/**
 * {@code GET /admin/dashboard}(006 contracts/api.md "대시보드"). {@code pendingReports}는 005 전에는 null(화면에서 카드 숨김).
 *
 * @param timeZone    계산에 쓴 IANA 시간대(요청한 관리자의 {@code users.time_zone})
 * @param generatedAt 캐시된 값을 계산한 시각(UTC)
 */
public record AdminDashboardResponse(Today today, Totals totals,
        @JsonInclude(JsonInclude.Include.ALWAYS) Long pendingReports, List<TrendDay> trend, String timeZone,
        Instant generatedAt) {

    public record Today(long signups, long publishedPosts, long comments) {
    }

    public record Totals(long members, long blogs, long publicPosts) {
    }

    public record TrendDay(LocalDate date, long signups, long publishedPosts) {
    }

    public static AdminDashboardResponse of(DashboardCounts counts, Long pendingReports, String timeZone,
            Instant generatedAt) {
        return new AdminDashboardResponse(
                new Today(counts.todaySignups(), counts.todayPublishedPosts(), counts.todayComments()),
                new Totals(counts.members(), counts.blogs(), counts.publicPosts()), pendingReports,
                counts.trend().stream().map(d -> new TrendDay(d.date(), d.signups(), d.publishedPosts())).toList(),
                timeZone, generatedAt);
    }
}
