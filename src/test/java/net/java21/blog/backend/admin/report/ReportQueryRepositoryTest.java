package net.java21.blog.backend.admin.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportChannel;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.repository.ReportRepository;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 005 T035: 신고 묶음 목록(대상별 묶음, 대상 미정 권리 침해는 한 건씩, 사유별 수, 채널 혼합), 묶음 수, 상세 조회, 받은 신고 수, 대기 신고 일괄
 * 처리. 묶음 목록은 대상 수와 관계없이 쿼리 2회.
 */
@JpaRepositoryTest
class ReportQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private QueryCounter queryCounter;

    private ReportQueryRepository repository;
    private JpaFixtures fx;
    private User owner;
    private User admin;
    private Blog blog;
    private Post post;
    private Comment comment;
    private User[] reporters;

    @BeforeEach
    void setUp() {
        repository = new ReportQueryRepository(queryFactory);
        fx = new JpaFixtures(em);
        owner = fx.user("owner");
        admin = fx.user("admin");
        blog = fx.blog(owner, "owner");
        post = fx.published(blog, "글", null, 0);
        comment = new Comment(post, owner, null, "댓글");
        em.persist(comment);
        reporters = new User[] {fx.user("r1"), fx.user("r2"), fx.user("r3")};
    }

    @Test
    void groupsByTargetWithReasonCountsAndKeepsUntargetedRightsRequestsSeparate() {
        Report first = member(reporters[0], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        member(reporters[1], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        member(reporters[2], ReportTargetType.POST, post.getId(), ReportReason.ABUSE);
        Report assigned = Report.rightsRequest("https://blog.java21.net/owner/1", ReportReason.COPYRIGHT, "근거",
                "me@example.com");
        assigned.assignTarget(ReportTargetType.POST, post.getId(), owner, blog);
        em.persist(assigned);
        member(reporters[0], ReportTargetType.COMMENT, comment.getId(), ReportReason.ABUSE);
        Report untargeted1 = rights("https://elsewhere.example/a");
        Report untargeted2 = rights("https://elsewhere.example/b");
        fx.flushAndClear();

        queryCounter.reset();
        Page<ReportGroupRow> page = repository.findGroups(
                new ReportQueryRepository.Filter(ReportStatus.PENDING, null, null), PageRequest.of(0, 20));
        assertThat(queryCounter.count()).isEqualTo(2);

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).hasSize(4);
        ReportGroupRow postGroup = page.getContent().stream()
                .filter(g -> g.targetType() == ReportTargetType.POST).findFirst().orElseThrow();
        assertThat(postGroup.representativeId()).isEqualTo(first.getId());
        assertThat(postGroup.targetId()).isEqualTo(post.getId());
        assertThat(postGroup.reportCount()).isEqualTo(4);
        assertThat(postGroup.reasons()).isEqualTo(Map.of(ReportReason.SPAM, 2L, ReportReason.ABUSE, 1L,
                ReportReason.COPYRIGHT, 1L));
        assertThat(postGroup.minChannel()).isEqualTo(ReportChannel.MEMBER);
        assertThat(postGroup.maxChannel()).isEqualTo(ReportChannel.RIGHTS_REQUEST);
        assertThat(postGroup.firstReportedAt()).isNotNull();
        assertThat(postGroup.action()).isNull();
        assertThat(page.getContent()).filteredOn(g -> g.targetType() == null)
                .extracting(ReportGroupRow::representativeId)
                .containsExactlyInAnyOrder(untargeted1.getId(), untargeted2.getId());

        assertThat(repository.countPendingGroups()).isEqualTo(4);
        Page<ReportGroupRow> comments = repository.findGroups(
                new ReportQueryRepository.Filter(ReportStatus.PENDING, ReportTargetType.COMMENT, null),
                PageRequest.of(0, 20));
        assertThat(comments.getContent()).singleElement()
                .satisfies(g -> assertThat(g.reasons()).isEqualTo(Map.of(ReportReason.ABUSE, 1L)));
        Page<ReportGroupRow> rights = repository.findGroups(
                new ReportQueryRepository.Filter(ReportStatus.PENDING, null, ReportChannel.RIGHTS_REQUEST),
                PageRequest.of(0, 2));
        assertThat(rights.getTotalElements()).isEqualTo(3);
        assertThat(rights.getContent()).hasSize(2);
    }

    @Test
    void resolvePendingClosesOnlyPendingRowsAndMovesThemToAnotherStatus() {
        Report a = member(reporters[0], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        Report b = member(reporters[1], ReportTargetType.POST, post.getId(), ReportReason.ABUSE);
        Report other = member(reporters[2], ReportTargetType.COMMENT, comment.getId(), ReportReason.SPAM);
        Report rights = rights("https://blog.java21.net/owner/1");
        rights.assignTarget(ReportTargetType.POST, post.getId(), owner, blog);
        fx.flushAndClear();

        List<Long> ids = reportRepository.findIdsByTargetAndStatus(ReportTargetType.POST, post.getId(),
                ReportStatus.PENDING);
        assertThat(ids).containsExactlyInAnyOrder(a.getId(), b.getId(), rights.getId());
        assertThat(reportRepository.findReporterIds(ids))
                .containsExactlyInAnyOrder(reporters[0].getId(), reporters[1].getId());
        assertThat(reportRepository.findRightsRequestsWithContact(ids)).extracting(Report::getId)
                .containsExactly(rights.getId());
        assertThat(reportRepository.existsByReporterIdAndTargetTypeAndTargetId(reporters[0].getId(),
                ReportTargetType.POST, post.getId())).isTrue();
        assertThat(reportRepository.existsByReporterIdAndTargetTypeAndTargetId(reporters[2].getId(),
                ReportTargetType.POST, post.getId())).isFalse();

        User adminRef = em.getReference(User.class, admin.getId());
        assertThat(reportRepository.resolvePending(ids, ReportStatus.ACTIONED, ReportAction.HIDE_CONTENT, "처리",
                adminRef, NOW)).isEqualTo(3);
        assertThat(reportRepository.resolvePending(ids, ReportStatus.DISMISSED, null, null, adminRef, NOW))
                .isZero();

        Report closed = repository.findWithPeople(a.getId());
        assertThat(closed.getStatus()).isEqualTo(ReportStatus.ACTIONED);
        assertThat(closed.getAction()).isEqualTo(ReportAction.HIDE_CONTENT);
        assertThat(closed.getResolutionNote()).isEqualTo("처리");
        assertThat(closed.getHandledAt()).isEqualTo(NOW);
        assertThat(closed.getHandledBy().getNickname()).isEqualTo("admin");
        assertThat(closed.getReporter().getNickname()).isEqualTo("r1");
        assertThat(em.find(Report.class, other.getId()).getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(repository.countPendingGroups()).isEqualTo(1);

        Page<ReportGroupRow> actioned = repository.findGroups(
                new ReportQueryRepository.Filter(ReportStatus.ACTIONED, null, null), PageRequest.of(0, 20));
        assertThat(actioned.getContent()).singleElement()
                .satisfies(g -> assertThat(g.action()).isEqualTo(ReportAction.HIDE_CONTENT));
        assertThat(repository.findWithPeople(-1L)).isNull();
    }

    @Test
    void sameTargetReportsNewestFirstWithReportersInOneQuery() {
        member(reporters[0], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        member(reporters[1], ReportTargetType.POST, post.getId(), ReportReason.ABUSE);
        Report rights = rights("https://blog.java21.net/owner/1");
        rights.assignTarget(ReportTargetType.POST, post.getId(), owner, blog);
        member(reporters[2], ReportTargetType.COMMENT, comment.getId(), ReportReason.SPAM);
        fx.flushAndClear();

        queryCounter.reset();
        List<Report> same = repository.findSameTarget(ReportTargetType.POST, post.getId());
        same.forEach(r -> {
            if (r.getReporter() != null) {
                r.getReporter().getNickname();
            }
        });
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(same).hasSize(3);
        assertThat(same.getFirst().getId()).isEqualTo(rights.getId());
    }

    @Test
    void countsReportsReceivedAsTargetAuthor() {
        member(reporters[0], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        member(reporters[1], ReportTargetType.COMMENT, comment.getId(), ReportReason.SPAM);
        rights("https://elsewhere.example/x");
        fx.flushAndClear();

        assertThat(repository.countReceivedBy(owner.getId())).isEqualTo(2);
        assertThat(repository.countReceivedBy(reporters[0].getId())).isZero();
        assertThat(repository.countPendingGroups()).isEqualTo(3);
    }

    private Report member(User reporter, ReportTargetType type, Long id, ReportReason reason) {
        Report report = Report.member(reporter, type, id, owner, blog, reason, null);
        em.persist(report);
        return report;
    }

    private Report rights(String url) {
        Report report = Report.rightsRequest(url, ReportReason.COPYRIGHT, "근거", "me@example.com");
        em.persist(report);
        return report;
    }
}
