package net.java21.blog.backend.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.admin.audit.AdminAuditLog;
import net.java21.blog.backend.admin.audit.AdminAuditLogRepository;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.repository.CommentRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.guestbook.repository.GuestbookEntryRepository;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.repository.ReportTargetPreviewRepository;
import net.java21.blog.backend.report.service.CommentTargetHandler;
import net.java21.blog.backend.report.service.GuestbookTargetHandler;
import net.java21.blog.backend.report.service.PostTargetHandler;
import net.java21.blog.backend.report.service.ReportTargetHandlers;
import net.java21.blog.backend.report.service.TrackbackTargetHandler;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.repository.TrackbackRepository;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 005 T036: 관리자 직접 숨김·해제. 사유 필수(해제는 선택), 상태가 바뀐 경우에만 작업 기록(대상 종류 이름, before·after status), 결과는
 * 바뀐 뒤 미리보기, 없는 대상·처리기 없는 종류 오류.
 */
@JpaRepositoryTest
class ContentHideServiceTest {

    private static final String IP = "203.0.113.9";

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
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

    private ContentHideService service;
    private JpaFixtures fx;
    private User admin;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        ReportTargetHandlers handlers = new ReportTargetHandlers(List.of(new PostTargetHandler(postRepository),
                new CommentTargetHandler(commentRepository), new GuestbookTargetHandler(guestbookRepository),
                new TrackbackTargetHandler(trackbackRepository)));
        service = new ContentHideService(handlers, new ReportTargetPreviewRepository(queryFactory,
                new SiteProperties("https://blog.example.test")), new AdminAuditService(auditLogRepository,
                        userRepository));
        fx = new JpaFixtures(em);
        admin = fx.user("admin");
        User owner = fx.user("owner");
        blog = fx.blog(owner, "owner");
        post = fx.published(blog, "글", null, 0);
    }

    @Test
    void hideAndUnhideAuditOnlyRealChanges() {
        ReportTargetPreview hidden = service.hide(admin.getId(), ReportTargetType.POST, post.getId(), " 불법 광고 ", IP);
        assertThat(hidden.state()).isEqualTo(ReportTargetPreview.State.HIDDEN);
        assertThat(service.hide(admin.getId(), ReportTargetType.POST, post.getId(), "다시", IP).state())
                .isEqualTo(ReportTargetPreview.State.HIDDEN);
        List<AdminAuditLog> logs = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("POST", post.getId());
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.getAction()).isEqualTo("CONTENT_HIDE");
            assertThat(log.getBefore()).isEqualTo(Map.of("status", "PUBLISHED"));
            assertThat(log.getAfter()).isEqualTo(Map.of("status", "HIDDEN"));
            assertThat(log.getReason()).isEqualTo("불법 광고");
            assertThat(log.getRequestIp()).isEqualTo(IP);
        });

        ReportTargetPreview restored = service.unhide(admin.getId(), ReportTargetType.POST, post.getId(), null, IP);
        assertThat(restored.state()).isEqualTo(ReportTargetPreview.State.ACTIVE);
        service.unhide(admin.getId(), ReportTargetType.POST, post.getId(), "", IP);
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("POST", post.getId()))
                .extracting(AdminAuditLog::getAction).containsExactly("CONTENT_UNHIDE", "CONTENT_HIDE");
    }

    @Test
    void everyKindUsesItsOwnAuditTarget() {
        GuestbookEntry entry = new GuestbookEntry(blog, admin, null, "방명록", false);
        em.persist(entry);
        Trackback trackback = new Trackback(post, null, "https://ext.example/1", "a".repeat(64), "외부", null, null,
                null);
        em.persist(trackback);
        fx.flushAndClear();

        service.hide(admin.getId(), ReportTargetType.GUESTBOOK, entry.getId(), "욕설", IP);
        service.hide(admin.getId(), ReportTargetType.TRACKBACK, trackback.getId(), "스팸", IP);
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("GUESTBOOK", entry.getId())).hasSize(1);
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("TRACKBACK", trackback.getId()))
                .hasSize(1);
        assertThat(ContentHideService.auditTarget(ReportTargetType.COMMENT)).isEqualTo("COMMENT");
        assertThat(ContentHideService.auditTarget(ReportTargetType.EXTERNAL_POST)).isEqualTo("EXTERNAL_POST");
    }

    @Test
    void reasonIsRequiredAndUnknownTargetsFail() {
        assertThatThrownBy(() -> service.hide(admin.getId(), ReportTargetType.POST, post.getId(), " ", IP))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement().satisfies(f -> assertThat(f.field())
                            .isEqualTo("reason"));
                });
        assertThatThrownBy(() -> service.hide(admin.getId(), ReportTargetType.COMMENT, 999_999L, "없음", IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONTENT_NOT_FOUND));
        assertThatThrownBy(() -> service.unhide(admin.getId(), ReportTargetType.EXTERNAL_BLOG, 1L, null, IP))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REPORT_ACTION_NOT_ALLOWED));
    }
}
