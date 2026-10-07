package net.java21.blog.backend.stats.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.stats.domain.BlogDailyVisit;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 방문 기록 저장소(T055): upsert 첫 호출 행 생성·다음 호출 증가, 전체 수 증가(수정 시각 그대로), 기간 조회, 각 쿼리 1회. */
@JpaRepositoryTest
class BlogVisitRepositoryTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-07");
    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private BlogVisitRepository repository;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private JdbcTemplate jdbc;

    private Blog blog;

    @BeforeEach
    void setUp() {
        JpaFixtures fx = new JpaFixtures(em);
        blog = fx.blog(fx.user("marco"), "marco");
        fx.flushAndClear();
    }

    @Test
    void upsertCreatesThenIncrements() {
        queryCounter.reset();
        repository.upsertVisit(blog.getId(), DAY, NOW);
        assertThat(queryCounter.count()).isEqualTo(1);
        repository.upsertVisit(blog.getId(), DAY, NOW.plusSeconds(60));
        repository.upsertVisit(blog.getId(), DAY.minusDays(1), NOW);

        assertThat(jdbc.queryForObject("SELECT visitors FROM blog_daily_visits WHERE blog_id = ? AND visit_date = ?",
                Integer.class, blog.getId(), DAY)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_daily_visits WHERE blog_id = ?", Integer.class,
                blog.getId())).isEqualTo(2);
    }

    @Test
    void incrementTotalIsAtomicAndKeepsUpdatedAt() {
        Object before = jdbc.queryForObject("SELECT updated_at FROM blogs WHERE id = ?", Object.class, blog.getId());
        queryCounter.reset();
        repository.incrementTotal(blog.getId());
        assertThat(queryCounter.count()).isEqualTo(1);
        repository.incrementTotal(blog.getId());

        assertThat(jdbc.queryForObject("SELECT total_visitors FROM blogs WHERE id = ?", Long.class, blog.getId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM blogs WHERE id = ?", Object.class, blog.getId()))
                .isEqualTo(before);
    }

    @Test
    void findRangeIsInclusiveAndOrdered() {
        repository.upsertVisit(blog.getId(), DAY, NOW);
        repository.upsertVisit(blog.getId(), DAY.minusDays(2), NOW);
        repository.upsertVisit(blog.getId(), DAY.minusDays(5), NOW);
        em.clear();

        queryCounter.reset();
        List<BlogDailyVisit> rows = repository.findRange(blog.getId(), DAY.minusDays(2), DAY);

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(rows).extracting(BlogDailyVisit::getVisitDate).containsExactly(DAY.minusDays(2), DAY);
        assertThat(rows).extracting(BlogDailyVisit::getVisitors).containsExactly(1, 1);
    }
}
