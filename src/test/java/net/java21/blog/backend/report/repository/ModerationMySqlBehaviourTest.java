package net.java21.blog.backend.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.admin.report.ReportGroupRow;
import net.java21.blog.backend.admin.report.ReportQueryRepository;
import net.java21.blog.backend.admin.user.AdminUserQueryRepository;
import net.java21.blog.backend.admin.user.dto.AdminUserSummary;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.portal.service.ScoreWeights;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.report.ReportsProperties;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.MySqlRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 005 T035: 실제 스키마(MySQL)에서 확인하는 신고 조회. 묶음 목록의 조건부 합계·묶음 열쇠 {@code COUNT(DISTINCT CASE ...)}, 감점 쿼리, 숨김
 * 상태 컬럼({@code status_before_hidden}), 관리자 회원 검색의 존재 하위 쿼리가 MySQL 문법·콜레이션에서 동작한다.
 */
@MySqlRepositoryTest
class ModerationMySqlBehaviourTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reportQueriesRunOnMySql() {
        JpaFixtures fx = new JpaFixtures(em);
        String suffix = Long.toString(System.nanoTime(), 36);
        User owner = fx.user("mo" + suffix);
        User reporter = fx.user("mr" + suffix);
        User admin = fx.user("ma" + suffix);
        Blog blog = fx.blog(owner, "mo" + suffix);
        Post post = fx.published(blog, "글", null, 0);
        Comment comment = new Comment(post, reporter, null, "댓글");
        em.persist(comment);
        em.persist(Report.member(reporter, ReportTargetType.POST, post.getId(), owner, blog, ReportReason.SPAM, null));
        em.persist(Report.member(admin, ReportTargetType.POST, post.getId(), owner, blog, ReportReason.ABUSE, null));
        em.persist(Report.member(admin, ReportTargetType.COMMENT, comment.getId(), reporter, blog, ReportReason.SPAM,
                null));
        em.persist(Report.rightsRequest("https://elsewhere.example/" + suffix, ReportReason.COPYRIGHT, "근거",
                "me@example.com"));
        em.flush();

        ReportQueryRepository queries = new ReportQueryRepository(queryFactory);
        long pendingBefore = queries.countPendingGroups();
        Page<ReportGroupRow> page = queries.findGroups(new ReportQueryRepository.Filter(ReportStatus.PENDING,
                ReportTargetType.POST, null), PageRequest.of(0, 100));
        assertThat(page.getContent()).filteredOn(g -> post.getId().equals(g.targetId())).singleElement()
                .satisfies(g -> {
                    assertThat(g.reportCount()).isEqualTo(2);
                    assertThat(g.reasons()).isEqualTo(Map.of(ReportReason.SPAM, 1L, ReportReason.ABUSE, 1L));
                });
        assertThat(queries.findGroups(new ReportQueryRepository.Filter(ReportStatus.PENDING, null, null),
                PageRequest.of(0, 1)).getTotalElements()).isEqualTo(pendingBefore);

        ReportTargetPreviewRepository previews = new ReportTargetPreviewRepository(queryFactory,
                new SiteProperties("https://blog.example.test"));
        post.hide();
        em.flush();
        assertThat(previews.preview(ReportTargetType.POST, post.getId()).state())
                .isEqualTo(ReportTargetPreview.State.HIDDEN);
        assertThat(jdbc.queryForMap("SELECT status, status_before_hidden FROM posts WHERE id = ?", post.getId()))
                .isEqualTo(Map.of("status", "HIDDEN", "status_before_hidden", "PUBLISHED"));

        List<Long> ids = reportRepository.findIdsByTargetAndStatus(ReportTargetType.POST, post.getId(),
                ReportStatus.PENDING);
        assertThat(reportRepository.resolvePending(ids, ReportStatus.ACTIONED, ReportAction.HIDE_CONTENT, null,
                admin, NOW)).isEqualTo(2);
        assertThat(queries.countPendingGroups()).isEqualTo(pendingBefore - 1);
        ReportPenaltyPolicy penalty = new ReportPenaltyPolicy(queryFactory, ReportsProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(penalty.penalties(List.of(blog.getId()), new ScoreWeights(1, 5, 10, 8, 48, 0.5)))
                .isEqualTo(Map.of(blog.getId(), 0.5));

        Page<AdminUserSummary> found = new AdminUserQueryRepository(queryFactory).search(
                new AdminUserQueryRepository.Search(null, null, "mo" + suffix), PageRequest.of(0, 20));
        assertThat(found.getContent()).singleElement()
                .satisfies(u -> assertThat(u.blogCount()).isEqualTo(1));
    }
}
