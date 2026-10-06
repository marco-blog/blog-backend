package net.java21.blog.backend.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDailyStat;
import net.java21.blog.backend.post.domain.PostDailyStatId;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 글 일별 통계 upsert·정리(003 T026, research P4): 네이티브 upsert 1회, 날짜별 행, 기준일 전 행만 건수 단위 삭제. */
@JpaRepositoryTest
class PostDailyStatsRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-06");

    @Autowired
    private EntityManager em;
    @Autowired
    private PostDailyStatsRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private Post post;

    @BeforeEach
    void setUp() {
        JpaFixtures fx = new JpaFixtures(em);
        post = fx.published(fx.blog(fx.user("marco"), "marco"), "글", null, 1);
        em.flush();
    }

    @Test
    void upsertViewCreatesThenIncrementsInOneQueryEach() {
        queryCounter.reset();
        assertThat(repository.upsertView(post.getId(), TODAY, NOW)).isPositive();
        assertThat(queryCounter.count()).isEqualTo(1);
        repository.upsertView(post.getId(), TODAY, NOW.plusSeconds(1));
        em.clear();

        PostDailyStat stat = repository.findById(new PostDailyStatId(post.getId(), TODAY)).orElseThrow();
        assertThat(stat.getViews()).isEqualTo(2);
        assertThat(stat.getReadCompletes()).isZero();
    }

    @Test
    void upsertReadCompleteSharesTheRowAndOtherDaysGetNewRows() {
        repository.upsertView(post.getId(), TODAY, NOW);
        queryCounter.reset();
        repository.upsertReadComplete(post.getId(), TODAY, NOW);
        assertThat(queryCounter.count()).isEqualTo(1);
        repository.upsertReadComplete(post.getId(), TODAY.plusDays(1), NOW);
        em.clear();

        PostDailyStat today = repository.findById(new PostDailyStatId(post.getId(), TODAY)).orElseThrow();
        assertThat(today.getViews()).isEqualTo(1);
        assertThat(today.getReadCompletes()).isEqualTo(1);
        PostDailyStat tomorrow = repository.findById(new PostDailyStatId(post.getId(), TODAY.plusDays(1)))
                .orElseThrow();
        assertThat(tomorrow.getViews()).isZero();
        assertThat(tomorrow.getReadCompletes()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void deleteOlderThanRemovesOnlyRowsBeforeTheDateUpToTheLimit() {
        for (int day = 0; day < 5; day++) {
            repository.upsertView(post.getId(), TODAY.minusDays(day), NOW);
        }
        LocalDate cutoff = TODAY.minusDays(1);

        queryCounter.reset();
        assertThat(repository.deleteOlderThan(cutoff, 2)).isEqualTo(2);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.deleteOlderThan(cutoff, 2)).isEqualTo(1);
        assertThat(repository.deleteOlderThan(cutoff, 2)).isZero();
        em.clear();

        assertThat(repository.findAll()).extracting(stat -> stat.getId().statDate())
                .containsExactlyInAnyOrder(TODAY, TODAY.minusDays(1));
    }
}
