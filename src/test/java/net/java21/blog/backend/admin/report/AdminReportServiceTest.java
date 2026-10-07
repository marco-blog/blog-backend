package net.java21.blog.backend.admin.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.admin.audit.AdminAuditLog;
import net.java21.blog.backend.admin.audit.AdminAuditLogRepository;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.report.dto.AssignTargetRequest;
import net.java21.blog.backend.admin.report.dto.ReportDetailResponse;
import net.java21.blog.backend.admin.report.dto.ReportGroupResponse;
import net.java21.blog.backend.admin.report.dto.ResolveReportRequest;
import net.java21.blog.backend.admin.report.dto.ResolveReportResponse;
import net.java21.blog.backend.admin.user.SuspensionService;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.comment.repository.CommentRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.guestbook.repository.GuestbookEntryRepository;
import net.java21.blog.backend.moderation.ContentHideService;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportAction;
import net.java21.blog.backend.report.domain.ReportChannel;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.event.ReportResolvedEvent;
import net.java21.blog.backend.report.repository.ReportRepository;
import net.java21.blog.backend.report.repository.ReportTargetPreviewRepository;
import net.java21.blog.backend.report.service.CommentTargetHandler;
import net.java21.blog.backend.report.service.GuestbookTargetHandler;
import net.java21.blog.backend.report.service.PostTargetHandler;
import net.java21.blog.backend.report.service.ReportTargetHandlers;
import net.java21.blog.backend.report.service.TrackbackTargetHandler;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.repository.TrackbackRepository;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 005 T036: 관리자 신고 처리. 목록(묶음 + 미리보기, 쿼리 수 고정), 요약, 상세(같은 대상 신고·받은 신고 수·연락 이메일), 대상 지정, 처리
 * (같은 대상 대기 신고를 한 번에 닫기, 숨김·정지 조치, 작업 기록, 커밋 뒤 이벤트), 검증·상태 오류.
 */
@JpaRepositoryTest
class AdminReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final String IP = "203.0.113.9";

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private GuestbookEntryRepository guestbookRepository;
    @Autowired
    private TrackbackRepository trackbackRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;
    @Autowired
    private QueryCounter queryCounter;

    private SuspensionService suspensionService;
    private ExternalReportActions externalActions;
    private ApplicationEventPublisher events;
    private AdminReportService service;
    private JpaFixtures fx;
    private User admin;
    private User owner;
    private User[] reporters;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        suspensionService = mock(SuspensionService.class);
        events = mock(ApplicationEventPublisher.class);
        externalActions = mock(ExternalReportActions.class);
        ReportTargetHandlers handlers = new ReportTargetHandlers(List.of(new PostTargetHandler(postRepository),
                new CommentTargetHandler(commentRepository), new GuestbookTargetHandler(guestbookRepository),
                new TrackbackTargetHandler(trackbackRepository)));
        ReportTargetPreviewRepository previews = new ReportTargetPreviewRepository(queryFactory,
                new SiteProperties("https://blog.example.test"));
        AdminAuditService audit = new AdminAuditService(auditLogRepository, userRepository);
        service = new AdminReportService(reportRepository, new ReportQueryRepository(queryFactory), previews, handlers,
                new ContentHideService(handlers, previews, audit), suspensionService, userRepository, audit, events,
                externalActions, Clock.fixed(NOW, ZoneOffset.UTC));
        fx = new JpaFixtures(em);
        admin = fx.user("admin");
        owner = fx.user("owner");
        reporters = new User[] {fx.user("r1"), fx.user("r2")};
        blog = fx.blog(owner, "owner");
        post = fx.published(blog, "신고된 글", null, 0);
    }

    @Test
    void listShowsGroupsWithPreviewsInFixedQueries() {
        Report first = member(reporters[0], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        member(reporters[1], ReportTargetType.POST, post.getId(), ReportReason.ABUSE);
        Comment comment = new Comment(post, reporters[0], null, "댓글");
        em.persist(comment);
        member(reporters[1], ReportTargetType.COMMENT, comment.getId(), ReportReason.SPAM);
        Report untargeted = rights("https://elsewhere.example/a");
        fx.flushAndClear();

        queryCounter.reset();
        Page<ReportGroupResponse> page = service.list(null, null, null, PageRequest.of(0, 20));
        assertThat(queryCounter.count()).isEqualTo(4);
        assertThat(page.getTotalElements()).isEqualTo(3);
        ReportGroupResponse postGroup = page.getContent().stream()
                .filter(g -> g.targetType() == ReportTargetType.POST).findFirst().orElseThrow();
        assertThat(postGroup.representativeId()).isEqualTo(first.getId());
        assertThat(postGroup.channel()).isEqualTo("MEMBER");
        assertThat(postGroup.reportCount()).isEqualTo(2);
        assertThat(postGroup.status()).isEqualTo(ReportStatus.PENDING);
        assertThat(postGroup.reasons()).extracting(ReportGroupResponse.ReasonCount::reason)
                .containsExactlyInAnyOrder(ReportReason.SPAM, ReportReason.ABUSE);
        assertThat(postGroup.target().title()).isEqualTo("신고된 글");
        ReportGroupResponse rightsGroup = page.getContent().stream().filter(g -> g.targetType() == null)
                .findFirst().orElseThrow();
        assertThat(rightsGroup.representativeId()).isEqualTo(untargeted.getId());
        assertThat(rightsGroup.channel()).isEqualTo("RIGHTS_REQUEST");
        assertThat(rightsGroup.target()).isNull();

        assertThat(service.list("PENDING", "COMMENT", "MEMBER", PageRequest.of(0, 20)).getTotalElements())
                .isEqualTo(1);
        assertThat(service.list("DISMISSED", "", " ", PageRequest.of(0, 20)).getTotalElements()).isZero();
        assertThat(service.summary().pendingCount()).isEqualTo(3);
        for (String[] bad : new String[][] {{"NOPE", null, null}, {null, "post", null}, {null, null, "X"}}) {
            assertThatThrownBy(() -> service.list(bad[0], bad[1], bad[2], PageRequest.of(0, 20)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    void detailListsSameTargetReportsAndHidesNothingFromAdmins() {
        Report first = member(reporters[0], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        Report rights = Report.rightsRequest("https://blog.example.test/owner/" + post.getId(),
                ReportReason.COPYRIGHT, "내 글을 베꼈다", "me@example.com");
        rights.assignTarget(ReportTargetType.POST, post.getId(), owner, blog);
        em.persist(rights);
        Report untargeted = rights("https://elsewhere.example/a");
        fx.flushAndClear();

        ReportDetailResponse detail = service.detail(first.getId());
        assertThat(detail.channel()).isEqualTo(ReportChannel.MEMBER);
        assertThat(detail.contactEmail()).isNull();
        assertThat(detail.reports()).hasSize(2);
        assertThat(detail.reports()).filteredOn(r -> r.reporter() != null).singleElement()
                .satisfies(r -> assertThat(r.reporter().nickname()).isEqualTo("r1"));
        assertThat(detail.targetUserReportCount()).isEqualTo(2);
        assertThat(detail.target().state()).isEqualTo(ReportTargetPreview.State.ACTIVE);

        ReportDetailResponse rightsDetail = service.detail(rights.getId());
        assertThat(rightsDetail.contactEmail()).isEqualTo("me@example.com");
        assertThat(rightsDetail.rightsBasis()).isEqualTo("내 글을 베꼈다");

        ReportDetailResponse lone = service.detail(untargeted.getId());
        assertThat(lone.target()).isNull();
        assertThat(lone.targetUserReportCount()).isNull();
        assertThat(lone.reports()).singleElement().satisfies(r -> assertThat(r.reporter()).isNull());
        assertError(() -> service.detail(999_999L), ErrorCode.REPORT_NOT_FOUND);
    }

    @Test
    void assignTargetOnlyForUntargetedRightsRequests() {
        Report untargeted = rights("https://elsewhere.example/a");
        Report targeted = member(reporters[0], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        fx.flushAndClear();

        assertThatThrownBy(() -> service.assignTarget(admin.getId(), untargeted.getId(),
                new AssignTargetRequest("POST", null), IP)).isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.fieldErrors()).singleElement()
                                .satisfies(f -> assertThat(f.field()).isEqualTo("targetId")));
        assertError(() -> service.assignTarget(admin.getId(), untargeted.getId(),
                new AssignTargetRequest("POST", 999_999L), IP), ErrorCode.CONTENT_NOT_FOUND);
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("REPORT", untargeted.getId()))
                .as("실패한 지정은 기록하지 않음").isEmpty();
        ReportDetailResponse assigned = service.assignTarget(admin.getId(), untargeted.getId(),
                new AssignTargetRequest("POST", post.getId()), IP);
        assertThat(assigned.target().id()).isEqualTo(post.getId());
        assertThat(assigned.targetUserReportCount()).isEqualTo(2);
        assertThat(assigned.reports()).hasSize(2);
        fx.flushAndClear();
        Report reloaded = em.find(Report.class, untargeted.getId());
        assertThat(reloaded.getTargetBlog().getId()).isEqualTo(blog.getId());
        AdminAuditLog log = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("REPORT", untargeted.getId())
                .getFirst();
        assertThat(log.getAction()).isEqualTo("REPORT_TARGET_ASSIGN");
        assertThat(log.getAdmin().getId()).isEqualTo(admin.getId());
        assertThat(log.getBefore()).containsEntry("targetType", null).containsEntry("targetId", null);
        assertThat(log.getAfter()).containsEntry("targetType", "POST")
                .hasEntrySatisfying("targetId", v -> assertThat(((Number) v).longValue()).isEqualTo(post.getId()));
        assertThat(log.getRequestIp()).isEqualTo(IP);
        assertError(() -> service.assignTarget(admin.getId(), targeted.getId(),
                new AssignTargetRequest("POST", post.getId()), IP), ErrorCode.REPORT_ALREADY_TARGETED);
    }

    @Test
    void hideActionClosesEveryPendingReportOfTheTargetAndNotifies() {
        Report first = member(reporters[0], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        Report second = member(reporters[1], ReportTargetType.POST, post.getId(), ReportReason.ABUSE);
        Report rights = Report.rightsRequest("https://blog.example.test/owner/" + post.getId(),
                ReportReason.COPYRIGHT, "근거", "me@example.com");
        rights.assignTarget(ReportTargetType.POST, post.getId(), owner, blog);
        em.persist(rights);
        Post otherPost = fx.published(blog, "다른 글", null, 1);
        Report other = member(reporters[0], ReportTargetType.POST, otherPost.getId(), ReportReason.SPAM);
        fx.flushAndClear();

        ResolveReportResponse response = service.resolve(admin.getId(), second.getId(),
                new ResolveReportRequest("ACTION", "HIDE_CONTENT", " 광고 ", null), IP);
        assertThat(response).isEqualTo(new ResolveReportResponse(3, ReportStatus.ACTIONED,
                ReportAction.HIDE_CONTENT));
        fx.flushAndClear();

        assertThat(em.find(Post.class, post.getId()).getStatus()).isEqualTo(PostStatus.HIDDEN);
        for (Report r : List.of(first, second, rights)) {
            Report closed = em.find(Report.class, r.getId());
            assertThat(closed.getStatus()).isEqualTo(ReportStatus.ACTIONED);
            assertThat(closed.getAction()).isEqualTo(ReportAction.HIDE_CONTENT);
            assertThat(closed.getResolutionNote()).isEqualTo("광고");
            assertThat(closed.getHandledAt()).isEqualTo(NOW);
        }
        assertThat(em.find(Report.class, other.getId()).getStatus()).isEqualTo(ReportStatus.PENDING);

        AdminAuditLog reportLog = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("REPORT",
                second.getId()).getFirst();
        assertThat(reportLog.getAction()).isEqualTo("REPORT_ACTION");
        assertThat(reportLog.getAfter()).containsEntry("status", "ACTIONED").containsEntry("action", "HIDE_CONTENT");
        assertThat(reportLog.getAfter().get("reportIds")).asInstanceOf(InstanceOfAssertFactories.LIST).hasSize(3);
        AdminAuditLog hideLog = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("POST", post.getId())
                .getFirst();
        assertThat(hideLog.getAction()).isEqualTo("CONTENT_HIDE");
        assertThat(hideLog.getReason()).isEqualTo("광고");

        ArgumentCaptor<ReportResolvedEvent> event = ArgumentCaptor.forClass(ReportResolvedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().decision()).isEqualTo(ReportStatus.ACTIONED);
        assertThat(event.getValue().targetType()).isEqualTo(ReportTargetType.POST);
        assertThat(event.getValue().members()).extracting(ReportResolvedEvent.MemberRecipient::reporterId)
                .containsExactlyInAnyOrder(reporters[0].getId(), reporters[1].getId());
        assertThat(event.getValue().rights()).singleElement().satisfies(r -> {
            assertThat(r.contactEmail()).isEqualTo("me@example.com");
            assertThat(r.toString()).doesNotContain("example.com");
        });

        assertError(() -> service.resolve(admin.getId(), first.getId(),
                new ResolveReportRequest("DISMISS", null, null, null), IP), ErrorCode.REPORT_ALREADY_RESOLVED);
    }

    @Test
    void hideWithoutNoteUsesTheReportNumberAsReason() {
        Comment comment = new Comment(post, reporters[1], null, "댓글");
        em.persist(comment);
        Report report = member(reporters[0], ReportTargetType.COMMENT, comment.getId(), ReportReason.ABUSE);
        fx.flushAndClear();

        service.resolve(admin.getId(), report.getId(), new ResolveReportRequest("ACTION", "HIDE_CONTENT", null, null),
                IP);
        fx.flushAndClear();
        AdminAuditLog hideLog = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("COMMENT",
                comment.getId()).getFirst();
        assertThat(hideLog.getReason()).isEqualTo("report #" + report.getId());
        assertThat(em.find(Report.class, report.getId()).getResolutionNote()).isNull();
    }

    @Test
    void suspendActionUsesTheTargetAuthor() {
        Report report = member(reporters[0], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        fx.flushAndClear();

        ResolveReportResponse response = service.resolve(admin.getId(), report.getId(),
                new ResolveReportRequest("ACTION", "SUSPEND_USER", null, "반복 스팸"), IP);
        assertThat(response.action()).isEqualTo(ReportAction.SUSPEND_USER);
        verify(suspensionService).suspend(admin.getId(), owner.getId(), "반복 스팸", IP);
        assertThat(em.find(Post.class, post.getId()).getStatus()).isEqualTo(PostStatus.PUBLISHED);
    }

    @Test
    void suspendIsNotAllowedForExternalTrackbacks() {
        Trackback external = new Trackback(post, null, "https://ext.example/1", "a".repeat(64), "외부", null, null,
                null);
        em.persist(external);
        Report report = Report.member(reporters[0], ReportTargetType.TRACKBACK, external.getId(), null, null,
                ReportReason.SPAM, null);
        em.persist(report);
        fx.flushAndClear();

        assertError(() -> service.resolve(admin.getId(), report.getId(),
                new ResolveReportRequest("ACTION", "SUSPEND_USER", null, "x"), IP), ErrorCode.REPORT_ACTION_NOT_ALLOWED);
        assertError(() -> service.resolve(admin.getId(), report.getId(),
                new ResolveReportRequest("ACTION", "REMOVE_FROM_PORTAL", null, null), IP),
                ErrorCode.REPORT_ACTION_NOT_ALLOWED);
        verify(suspensionService, never()).suspend(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void externalActionsNeedTheMatchingExternalTarget() {
        Report postReport = Report.member(reporters[0], ReportTargetType.EXTERNAL_POST, 41L, owner, null,
                ReportReason.SPAM, null);
        Report blogReport = Report.member(reporters[1], ReportTargetType.EXTERNAL_BLOG, 42L, owner, null,
                ReportReason.SPAM, null);
        em.persist(postReport);
        em.persist(blogReport);
        fx.flushAndClear();

        assertError(() -> service.resolve(admin.getId(), postReport.getId(),
                new ResolveReportRequest("ACTION", "BLOCK_EXTERNAL_BLOG", null, null), IP),
                ErrorCode.REPORT_ACTION_NOT_ALLOWED);
        assertError(() -> service.resolve(admin.getId(), blogReport.getId(),
                new ResolveReportRequest("ACTION", "REMOVE_FROM_PORTAL", null, null), IP),
                ErrorCode.REPORT_ACTION_NOT_ALLOWED);

        ResolveReportResponse removed = service.resolve(admin.getId(), postReport.getId(),
                new ResolveReportRequest("ACTION", "REMOVE_FROM_PORTAL", null, null), IP);
        assertThat(removed.action()).isEqualTo(ReportAction.REMOVE_FROM_PORTAL);
        verify(externalActions).removeFromPortal(admin.getId(), 41L, "report #" + postReport.getId(), IP);
        ResolveReportResponse blocked = service.resolve(admin.getId(), blogReport.getId(),
                new ResolveReportRequest("ACTION", "BLOCK_EXTERNAL_BLOG", "피싱", null), IP);
        assertThat(blocked.action()).isEqualTo(ReportAction.BLOCK_EXTERNAL_BLOG);
        verify(externalActions).blockExternalBlog(admin.getId(), 42L, "피싱", IP);
    }

    @Test
    void dismissClosesAnUntargetedRightsRequestAlone() {
        Report untargeted = rights("https://elsewhere.example/a");
        Report another = rights("https://elsewhere.example/b");
        fx.flushAndClear();

        assertError(() -> service.resolve(admin.getId(), untargeted.getId(),
                new ResolveReportRequest("ACTION", "HIDE_CONTENT", null, null), IP), ErrorCode.REPORT_TARGET_REQUIRED);
        ResolveReportResponse response = service.resolve(admin.getId(), untargeted.getId(),
                new ResolveReportRequest("DISMISS", null, "권리 침해 아님", null), IP);
        assertThat(response).isEqualTo(new ResolveReportResponse(1, ReportStatus.DISMISSED, null));
        fx.flushAndClear();
        assertThat(em.find(Report.class, another.getId()).getStatus()).isEqualTo(ReportStatus.PENDING);
        AdminAuditLog log = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("REPORT", untargeted.getId())
                .getFirst();
        assertThat(log.getAction()).isEqualTo("REPORT_DISMISS");
        assertThat(log.getBefore()).isEqualTo(Map.of("status", "PENDING"));
        ArgumentCaptor<ReportResolvedEvent> event = ArgumentCaptor.forClass(ReportResolvedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().targetType()).isNull();
        assertThat(event.getValue().members()).isEmpty();
        assertThat(event.getValue().rights()).hasSize(1);
    }

    @Test
    void decisionAndActionAreValidated() {
        Report report = member(reporters[0], ReportTargetType.POST, post.getId(), ReportReason.SPAM);
        fx.flushAndClear();

        for (ResolveReportRequest bad : List.of(new ResolveReportRequest(null, null, null, null),
                new ResolveReportRequest("MAYBE", null, null, null),
                new ResolveReportRequest("ACTION", null, null, null),
                new ResolveReportRequest("ACTION", "DELETE", null, null),
                new ResolveReportRequest("DISMISS", null, "가".repeat(501), null))) {
            assertError(() -> service.resolve(admin.getId(), report.getId(), bad, IP), ErrorCode.VALIDATION_FAILED);
        }
        assertError(() -> service.resolve(admin.getId(), 999_999L, new ResolveReportRequest("DISMISS", null, null,
                null), IP), ErrorCode.REPORT_NOT_FOUND);
        fx.flushAndClear();
        assertThat(em.find(Report.class, report.getId()).getStatus()).isEqualTo(ReportStatus.PENDING);
    }

    private Report member(User reporter, ReportTargetType type, Long id, ReportReason reason) {
        Report report = Report.member(reporter, type, id, owner, blog, reason, "설명");
        em.persist(report);
        return report;
    }

    private Report rights(String url) {
        Report report = Report.rightsRequest(url, ReportReason.COPYRIGHT, "근거", "me@example.com");
        em.persist(report);
        return report;
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
