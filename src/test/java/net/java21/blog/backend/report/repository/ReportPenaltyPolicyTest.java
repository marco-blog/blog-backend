package net.java21.blog.backend.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.portal.service.ScoreWeights;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.report.ReportsProperties;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 005 T040: 인기 점수 블로그 감점. 최근 90일 안에 인정된 신고가 있는 블로그만 {@code 1 - reportPenalty} 배수, 기각·대기·오래된 신고는
 * 감점 없음, 블로그 수와 무관하게 쿼리 1회.
 */
@JpaRepositoryTest
class ReportPenaltyPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final ScoreWeights WEIGHTS = new ScoreWeights(1, 5, 10, 8, 48, 0.5);

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private QueryCounter queryCounter;

    private ReportPenaltyPolicy policy;
    private JpaFixtures fx;
    private User reporter;
    private User admin;

    @BeforeEach
    void setUp() {
        policy = new ReportPenaltyPolicy(queryFactory, ReportsProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
        fx = new JpaFixtures(em);
        reporter = fx.user("reporter");
        admin = fx.user("admin");
    }

    @Test
    void onlyRecentlyActionedReportsPenalizeTheirBlog() {
        Blog actioned = blogWithReport("actioned", ReportStatus.ACTIONED, NOW.minus(Duration.ofDays(10)));
        Blog dismissed = blogWithReport("dismissed", ReportStatus.DISMISSED, NOW.minus(Duration.ofDays(1)));
        Blog pending = blogWithReport("pending", null, null);
        Blog old = blogWithReport("old", ReportStatus.ACTIONED, NOW.minus(Duration.ofDays(91)));
        Blog edge = blogWithReport("edge", ReportStatus.ACTIONED, NOW.minus(Duration.ofDays(90)));
        fx.flushAndClear();

        queryCounter.reset();
        Map<Long, Double> penalties = policy.penalties(List.of(actioned.getId(), dismissed.getId(), pending.getId(),
                old.getId(), edge.getId()), WEIGHTS);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(penalties).containsOnly(Map.entry(actioned.getId(), 0.5), Map.entry(edge.getId(), 0.5));

        assertThat(policy.penalties(List.of(actioned.getId()), new ScoreWeights(1, 5, 10, 8, 48, 1.5)))
                .containsEntry(actioned.getId(), 0.0);
        assertThat(policy.penalties(List.of(actioned.getId()), new ScoreWeights(1, 5, 10, 8, 48, 0)))
                .containsEntry(actioned.getId(), 1.0);
        assertThat(policy.penalties(List.of(dismissed.getId()), WEIGHTS)).isEmpty();
    }

    @Test
    void emptyCandidatesNeedNoQuery() {
        queryCounter.reset();
        assertThat(policy.penalties(List.of(), WEIGHTS)).isEmpty();
        assertThat(policy.penalties(null, WEIGHTS)).isEmpty();
        assertThat(queryCounter.count()).isZero();
    }

    private Blog blogWithReport(String handle, ReportStatus status, Instant handledAt) {
        User owner = fx.user(handle);
        Blog blog = fx.blog(owner, handle);
        Post post = fx.published(blog, "글", null, 0);
        Report report = Report.member(reporter, ReportTargetType.POST, post.getId(), owner, blog, ReportReason.SPAM,
                null);
        em.persist(report);
        em.flush();
        if (status != null) {
            reportRepository.resolvePending(List.of(report.getId()), status,
                    status == ReportStatus.ACTIONED ? ReportAction.HIDE_CONTENT : null, null, admin, handledAt);
        }
        return blog;
    }
}
