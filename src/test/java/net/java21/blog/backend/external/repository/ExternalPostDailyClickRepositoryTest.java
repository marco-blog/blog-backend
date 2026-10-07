package net.java21.blog.backend.external.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostDailyClick;
import net.java21.blog.backend.external.domain.ExternalPostDailyClickId;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 007 T047: 일별 클릭 upsert({@code INSERT … ON DUPLICATE KEY UPDATE}, H2 MySQL 모드에서도 동작) — 두 번이면 {@code clicks} 2, 다른
 * 날은 다른 행, {@code click_count}와 함께 쿼리 2회 (research E14).
 */
@JpaRepositoryTest
class ExternalPostDailyClickRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T10:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalPostDailyClickRepository dailyClicks;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private QueryCounter queryCounter;

    @Test
    void upsertAddsOnePerCallAndDayAndClickCountFollows() {
        JpaFixtures fx = new JpaFixtures(em);
        ExternalFixtures x = new ExternalFixtures(em);
        var topic = fx.topic(fx.topic(null, "knowledge", 0), "it-internet", 0);
        ExternalBlog blog = x.blog(fx.user("m"), topic, ExternalBlogStatus.ACTIVE);
        ExternalPost post = x.post(blog, "p", topic, NOW);
        fx.flushAndClear();
        LocalDate today = LocalDate.parse("2026-10-06");

        queryCounter.reset();
        postRepository.incrementClick(post.getId());
        dailyClicks.upsertClick(post.getId(), today, NOW);
        assertThat(queryCounter.count()).isEqualTo(2);
        postRepository.incrementClick(post.getId());
        dailyClicks.upsertClick(post.getId(), today, NOW);
        dailyClicks.upsertClick(post.getId(), today.plusDays(1), NOW);
        fx.flushAndClear();

        assertThat(dailyClicks.findById(new ExternalPostDailyClickId(post.getId(), today)))
                .map(ExternalPostDailyClick::getClicks).contains(2);
        assertThat(dailyClicks.findById(new ExternalPostDailyClickId(post.getId(), today.plusDays(1))))
                .map(ExternalPostDailyClick::getClicks).contains(1);
        assertThat(postRepository.findById(post.getId())).map(ExternalPost::getClickCount).contains(2);
    }
}
