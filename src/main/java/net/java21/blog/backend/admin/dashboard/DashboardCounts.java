package net.java21.blog.backend.admin.dashboard;

import java.time.LocalDate;
import java.util.List;

/**
 * 콘솔 대시보드 수치(006 FR-103, research A3). 처리 대기 신고 수는 캐시하지 않으므로 여기에 넣지 않는다.
 *
 * @param todaySignups        관리자 시간대의 오늘 가입(탈퇴 포함)
 * @param todayPublishedPosts 오늘 처음 발행한 글(지금 상태·공개 범위와 무관)
 * @param todayComments       오늘 쓴 댓글(비회원·지금 상태와 무관)
 * @param members             ACTIVE 회원
 * @param blogs               ACTIVE 블로그
 * @param publicPosts         PUBLISHED·PUBLIC 글
 * @param trend               오늘 포함 7일(오래된 날 먼저, 빈 날 0)
 */
public record DashboardCounts(long todaySignups, long todayPublishedPosts, long todayComments, long members,
        long blogs, long publicPosts, List<Day> trend) {

    public DashboardCounts {
        trend = List.copyOf(trend);
    }

    /** 하루치 가입·발행 수(날짜는 관리자 시간대). */
    public record Day(LocalDate date, long signups, long publishedPosts) {
    }
}
