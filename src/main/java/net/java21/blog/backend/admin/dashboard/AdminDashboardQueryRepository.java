package net.java21.blog.backend.admin.dashboard;

import static net.java21.blog.backend.blog.domain.QBlog.blog;
import static net.java21.blog.backend.comment.domain.QComment.comment;
import static net.java21.blog.backend.post.domain.QPost.post;
import static net.java21.blog.backend.user.domain.QUser.user;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.user.domain.UserStatus;
import org.springframework.stereotype.Repository;

/**
 * 콘솔 대시보드 계산(006 FR-103, research A3). 쿼리 6회: 7일치 가입 시각, 7일치 첫 발행 시각, 오늘 댓글 수, 전체 회원·블로그·공개 글 수.
 * 날짜 묶기는 관리자 시간대로 애플리케이션에서 한다(DB 시간대 함수의 방언 차이를 피함). 시각 조건 칼럼에는 인덱스가 없어
 * (선택 인덱스 제안 1~3, marco 승인 대기) 5분 캐시({@link AdminDashboardService})가 감싼다.
 */
@Repository
public class AdminDashboardQueryRepository {

    static final int TREND_DAYS = 7;

    private final JPAQueryFactory queryFactory;

    public AdminDashboardQueryRepository(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    /** {@code zone}의 오늘(지금 {@code now}가 든 날)과 그 앞 6일. */
    public DashboardCounts compute(ZoneId zone, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate first = today.minusDays(TREND_DAYS - 1L);
        Instant from = first.atStartOfDay(zone).toInstant();
        Instant todayStart = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();

        List<Instant> signups = queryFactory.select(user.createdAt).from(user)
                .where(user.createdAt.goe(from), user.createdAt.lt(end))
                .fetch();
        List<Instant> published = queryFactory.select(post.publishedAt).from(post)
                .where(post.publishedAt.goe(from), post.publishedAt.lt(end))
                .fetch();
        long comments = count(queryFactory.select(comment.count()).from(comment)
                .where(comment.createdAt.goe(todayStart), comment.createdAt.lt(end))
                .fetchOne());
        long members = count(queryFactory.select(user.count()).from(user)
                .where(user.status.eq(UserStatus.ACTIVE)).fetchOne());
        long blogs = count(queryFactory.select(blog.count()).from(blog)
                .where(blog.status.eq(BlogStatus.ACTIVE)).fetchOne());
        long publicPosts = count(queryFactory.select(post.count()).from(post)
                .where(post.status.eq(PostStatus.PUBLISHED), post.visibility.eq(PostVisibility.PUBLIC)).fetchOne());

        Map<LocalDate, long[]> days = new LinkedHashMap<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            days.put(day, new long[2]);
        }
        signups.forEach(at -> days.get(LocalDate.ofInstant(at, zone))[0]++);
        published.forEach(at -> days.get(LocalDate.ofInstant(at, zone))[1]++);
        List<DashboardCounts.Day> trend = new ArrayList<>();
        days.forEach((day, counts) -> trend.add(new DashboardCounts.Day(day, counts[0], counts[1])));
        long[] todayCounts = days.get(today);
        return new DashboardCounts(todayCounts[0], todayCounts[1], comments, members, blogs, publicPosts, trend);
    }

    private static long count(Long value) {
        return value == null ? 0 : value;
    }
}
