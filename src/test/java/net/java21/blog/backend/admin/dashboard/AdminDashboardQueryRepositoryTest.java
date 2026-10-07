package net.java21.blog.backend.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * 006 T020(FR-103, research A3): 관리자 시간대의 날짜 경계(Asia/Seoul·UTC), 7일 추이(빈 날 0, 오래된 날 먼저), 오늘·전체 수,
 * 쿼리 6회. 같은 H2를 다른 시험과 함께 쓰므로 데이터를 넣기 전후 값의 차이로 확인한다.
 */
@JpaRepositoryTest
@Import(AdminDashboardQueryRepository.class)
class AdminDashboardQueryRepositoryTest {

    /** 서울은 10월 8일 00:30, UTC는 10월 7일 15:30. */
    private static final Instant NOW = Instant.parse("2026-10-07T15:30:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Autowired
    private EntityManager em;
    @Autowired
    private AdminDashboardQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private DashboardCounts seoulBefore;
    private DashboardCounts utcBefore;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        seoulBefore = repository.compute(SEOUL, NOW);
        utcBefore = repository.compute(UTC, NOW);

        joined("d-seoul-today", "2026-10-07T15:10:00Z");     // 서울 10-08(오늘), UTC 10-07(오늘)
        joined("d-seoul-yday", "2026-10-07T14:59:59Z");      // 서울 10-07, UTC 10-07(오늘)
        joined("d-first", "2026-10-01T15:00:00Z");           // 서울 10-02(첫날), UTC 10-01(첫날)
        joined("d-out", "2026-10-01T14:59:59Z");             // 서울 범위 밖, UTC 10-01(첫날)
        User withdrawn = joined("d-gone", "2026-10-07T15:20:00Z");
        withdrawn.withdraw(NOW);                             // 탈퇴해도 그날 가입자에 든다, 전체 회원에서는 빠진다

        User owner = joined("d-owner", "2026-09-01T00:00:00Z");
        Blog blog = fx.blog(owner, "dashblog");
        Post today = fx.publishedText(blog, "오늘 글", "본문", null, Instant.parse("2026-10-07T15:05:00Z"));
        fx.publishedText(blog, "어제 글", "본문", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE,
                Instant.parse("2026-10-06T16:00:00Z"));            // 서울 10-07, UTC 10-06. 비공개라도 발행 수에 든다
        fx.publishedText(blog, "지운 글", "본문", null, PostStatus.DELETED, PostVisibility.PUBLIC,
                Instant.parse("2026-10-03T00:00:00Z"));            // 지금 상태 무관
        fx.post(blog, "초안", null, PostStatus.DRAFT, PostVisibility.PUBLIC, 0);
        Comment c1 = new Comment(today, owner, null, "오늘 댓글");
        Comment c2 = Comment.byGuest(today, null, "손님", "$2a$hash", "127.0.0.1", "어제 댓글", false);
        em.persist(c1);
        em.persist(c2);
        em.flush();
        createdAt("comments", c1.getId(), "2026-10-07T15:15:00Z");   // 서울 오늘, UTC 오늘
        createdAt("comments", c2.getId(), "2026-10-07T14:00:00Z");   // 서울 어제, UTC 오늘
        em.clear();
    }

    @Test
    void seoulBoundaries() {
        queryCounter.reset();
        DashboardCounts after = repository.compute(SEOUL, NOW);
        assertThat(queryCounter.count()).isLessThanOrEqualTo(6);

        assertThat(after.trend()).hasSize(7);
        assertThat(after.trend()).extracting(DashboardCounts.Day::date)
                .startsWith(LocalDate.of(2026, 10, 2)).endsWith(LocalDate.of(2026, 10, 8));
        assertThat(after.todaySignups() - seoulBefore.todaySignups()).isEqualTo(2);
        assertThat(after.todayPublishedPosts() - seoulBefore.todayPublishedPosts()).isEqualTo(1);
        assertThat(after.todayComments() - seoulBefore.todayComments()).isEqualTo(1);
        assertThat(signupDelta(after, seoulBefore)).containsExactly(1L, 0L, 0L, 0L, 0L, 1L, 2L);
        assertThat(publishDelta(after, seoulBefore)).as("10-03 00:00Z 발행은 서울 10-03 09:00(둘째 칸)")
                .containsExactly(0L, 1L, 0L, 0L, 0L, 1L, 1L);
    }

    @Test
    void utcBoundariesAndTotals() {
        DashboardCounts after = repository.compute(UTC, NOW);

        assertThat(after.trend()).extracting(DashboardCounts.Day::date)
                .startsWith(LocalDate.of(2026, 10, 1)).endsWith(LocalDate.of(2026, 10, 7));
        assertThat(after.todaySignups() - utcBefore.todaySignups()).isEqualTo(3);
        assertThat(after.todayComments() - utcBefore.todayComments()).isEqualTo(2);
        assertThat(signupDelta(after, utcBefore)).containsExactly(2L, 0L, 0L, 0L, 0L, 0L, 3L);
        assertThat(publishDelta(after, utcBefore)).containsExactly(0L, 0L, 1L, 0L, 0L, 1L, 1L);
        assertThat(after.members() - utcBefore.members()).as("ACTIVE 회원(탈퇴 제외)").isEqualTo(5);
        assertThat(after.blogs() - utcBefore.blogs()).isEqualTo(1);
        assertThat(after.publicPosts() - utcBefore.publicPosts()).as("PUBLISHED·PUBLIC만").isEqualTo(1);
    }

    private User joined(String nickname, String at) {
        User user = fx.user(nickname);
        fx.joinedAt(user, Instant.parse(at));
        return user;
    }

    private void createdAt(String table, Long id, String at) {
        em.createNativeQuery("UPDATE " + table + " SET created_at = :t WHERE id = :id")
                .setParameter("t", Instant.parse(at)).setParameter("id", id).executeUpdate();
    }

    private static List<Long> signupDelta(DashboardCounts after, DashboardCounts before) {
        return java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> after.trend().get(i).signups() - before.trend().get(i).signups()).toList();
    }

    private static List<Long> publishDelta(DashboardCounts after, DashboardCounts before) {
        return java.util.stream.IntStream.range(0, 7)
                .mapToObj(i -> after.trend().get(i).publishedPosts() - before.trend().get(i).publishedPosts())
                .toList();
    }
}
