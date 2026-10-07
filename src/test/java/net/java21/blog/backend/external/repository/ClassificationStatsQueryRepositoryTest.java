package net.java21.blog.backend.external.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.external.ClassificationStatsService;
import net.java21.blog.backend.external.classify.KeywordDictionary;
import net.java21.blog.backend.external.domain.ClassificationReview;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.dto.ClassificationStatsResponse;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.repository.TopicQueryRepository;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.github.benmanes.caffeine.cache.Ticker;

/**
 * 007 T062: 분류 현황(FR-122, SC-019, research E11) — 최근 30일 사람이 정한 글의 분류 정확도, 확정한 검수의 최종 주제 정확도, 주제·출처별
 * 분포, 대기 수, 5분 캐시, 쿼리 4회.
 */
@JpaRepositoryTest
@Import(ClassificationStatsQueryRepository.class)
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ClassificationStatsQueryRepositoryTest {

    private static final Instant NOW = JpaFixtures.T0.plus(Duration.ofDays(40));

    @Autowired
    private EntityManager em;
    @Autowired
    private ClassificationStatsQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private ExternalFixtures x;
    private User admin;
    private Topic it;
    private Topic science;
    private ExternalBlog blog;

    @BeforeEach
    void setUp() {
        JpaFixtures f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        admin = f.user("admin");
        Topic major = f.topic(null, "knowledge", 1);
        it = f.topic(major, "it-internet", 1);
        science = f.topic(major, "science", 2);
        blog = x.blog(null, it, ExternalBlogStatus.ACTIVE);
    }

    /** 분류기가 {@code predicted}로 본 글을 사람이 {@code finalTopic}으로 정함. */
    private ExternalPost decided(Topic predicted, Topic finalTopic, TopicSource source, Instant at) {
        ExternalPost post = x.post(blog, "P", it, NOW.minus(Duration.ofDays(1)));
        if (predicted != null) {
            post.recordClassifier(predicted, 0.5, "keyword-v1");
        }
        post.changeTopic(finalTopic, source, at);
        return post;
    }

    private ClassificationReview confirmed(Topic topic, Instant at) {
        ClassificationReview review = x.review(x.post(blog, "R", it, NOW.minus(Duration.ofDays(2))), science, 0.3);
        review.confirm(topic, admin, at);
        return review;
    }

    private ClassificationStatsService service(MutableClock clock, Ticker ticker) {
        SystemSettingsService settings = Mockito.mock(SystemSettingsService.class);
        Mockito.when(settings.autoClassifyMinConfidence()).thenReturn(0.7);
        return new ClassificationStatsService(repository, settings,
                new KeywordDictionary(Mockito.mock(TopicQueryRepository.class)), clock, ticker);
    }

    @Test
    void emptyWindowHasNullRates() {
        ClassificationStatsResponse stats = service(new MutableClock(NOW), Ticker.systemTicker()).stats();

        assertThat(stats.classifierAccuracy().sample()).isZero();
        assertThat(stats.classifierAccuracy().rate()).isNull();
        assertThat(stats.finalAccuracy().rate()).isNull();
        assertThat(stats.pendingReviews()).isZero();
        assertThat(stats.minConfidence()).isEqualTo(0.7);
        assertThat(stats.classifierVersion()).isEqualTo("keyword-v1");
        assertThat(stats.window().to()).isEqualTo(NOW);
        assertThat(stats.window().from()).isEqualTo(NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void computesAccuracyDistributionAndPendingInFourQueriesThenCaches() {
        Instant recent = NOW.minus(Duration.ofDays(3));
        decided(science, science, TopicSource.OWNER, recent);
        decided(it, science, TopicSource.REVIEW, recent);
        decided(science, science, TopicSource.REVIEW, recent);
        decided(science, it, TopicSource.OWNER, NOW.minus(Duration.ofDays(31)));
        decided(null, science, TopicSource.OWNER, recent);
        ExternalPost auto = x.post(blog, "Auto", it, NOW.minus(Duration.ofDays(1)));
        auto.recordClassifier(science, 0.9, "keyword-v1");
        auto.changeTopic(science, TopicSource.AUTO, recent);
        confirmed(it, recent);
        confirmed(science, recent);
        confirmed(it, NOW.minus(Duration.ofDays(35)));
        x.review(x.post(blog, "Wait", it, NOW.minus(Duration.ofDays(50))), null, 0.1);
        x.review(x.removed(blog, "Gone", it, RemovedReason.ADMIN), null, 0.1);
        em.flush();
        em.clear();

        MutableClock clock = new MutableClock(NOW);
        long[] nanos = {0};
        ClassificationStatsService service = service(clock, () -> nanos[0]);
        queryCounter.reset();
        ClassificationStatsResponse stats = service.stats();

        assertThat(queryCounter.count()).isLessThanOrEqualTo(4);
        assertThat(stats.classifierAccuracy().sample()).isEqualTo(3);
        assertThat(stats.classifierAccuracy().correct()).isEqualTo(2);
        assertThat(stats.classifierAccuracy().rate()).isEqualTo(2.0 / 3);
        assertThat(stats.finalAccuracy().sample()).isEqualTo(2);
        assertThat(stats.finalAccuracy().unchanged()).isEqualTo(1);
        assertThat(stats.finalAccuracy().rate()).isEqualTo(0.5);
        assertThat(stats.pendingReviews()).isEqualTo(1);
        ClassificationStatsResponse.TopicCount scienceCount = stats.distribution().stream()
                .filter(c -> c.topicId() == science.getId()).findFirst().orElseThrow();
        assertThat(scienceCount.bySource()).containsEntry(TopicSource.OWNER, 2L)
                .containsEntry(TopicSource.REVIEW, 2L).containsEntry(TopicSource.AUTO, 1L)
                .containsEntry(TopicSource.DEFAULT, 0L);
        assertThat(scienceCount.total()).isEqualTo(5);
        assertThat(stats.distribution().getFirst().topicId()).isEqualTo(science.getId());
        assertThat(stats.generatedAt()).isEqualTo(NOW);

        // 5분 안에는 같은 결과(쿼리 없음), 지나면 다시 계산
        clock.advance(Duration.ofMinutes(4));
        nanos[0] = Duration.ofMinutes(4).toNanos();
        queryCounter.reset();
        assertThat(service.stats().generatedAt()).isEqualTo(NOW);
        assertThat(queryCounter.count()).isZero();
        nanos[0] = Duration.ofMinutes(6).toNanos();
        assertThat(service.stats().generatedAt()).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
    }
}
