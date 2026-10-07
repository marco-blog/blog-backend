package net.java21.blog.backend.admin.external;

import static net.java21.blog.backend.support.ExternalTestKit.rss;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.audit.AdminAuditLog;
import net.java21.blog.backend.admin.audit.AdminAuditLogRepository;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RegistrationType;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.dto.AdminExternalBlogResponse;
import net.java21.blog.backend.external.dto.AdminExternalPostResponse;
import net.java21.blog.backend.external.feed.FeedDiscovery;
import net.java21.blog.backend.external.feed.FeedParser;
import net.java21.blog.backend.external.repository.ExternalBlogQueryRepository;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.notification.domain.Notification;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.notification.repository.NotificationRepository;
import net.java21.blog.backend.notification.service.ExternalBlogNotifier;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.portal.repository.PortalExclusionRepository;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.ExternalTestKit;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.StubHttpServer;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.topic.repository.TopicRepository;
import net.java21.blog.backend.topic.service.TopicService;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 007 T026: 관리자 직접 등록·승인·거절·기본 주제 변경과 남긴 글 이어받기(US1 AS6·AS7, FR-111, 결정 표 24번). H2 + 피드는
 * {@link StubHttpServer}, 작업 기록·알림은 실제 저장소로 확인한다.
 */
@JpaRepositoryTest
@Import(ExternalBlogQueryRepository.class)
class AdminExternalBlogServiceTest {

    private static final String RSS = "application/rss+xml; charset=UTF-8";
    private static final String IP = "203.0.113.7";

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private ExternalBlogQueryRepository queryRepository;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private PortalExclusionRepository exclusionRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TopicRepository topicRepository;
    @Autowired
    private AdminAuditLogRepository auditRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private StubHttpServer server;
    private MutableClock clock;
    private ApplicationEventPublisher events;
    private AdminExternalBlogService service;
    private JpaFixtures f;
    private ExternalFixtures x;
    private User admin;
    private User member;
    private Topic major;
    private Topic topic;
    private Topic other;

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
        clock = new MutableClock(JpaFixtures.T0.plusSeconds(3600));
        f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        admin = f.user("admin");
        member = f.user("member");
        major = f.topic(null, "knowledge", 1);
        topic = f.topic(major, "it-internet", 1);
        other = f.topic(major, "science", 2);
        events = mock(ApplicationEventPublisher.class);
        TopicService topics = new TopicService(topicRepository, null, null, null, null, null,
                PortalProperties.defaults());
        FeedDiscovery discovery = new FeedDiscovery(ExternalTestKit.fetcher(server), new FeedParser(), clock);
        service = new AdminExternalBlogService(blogRepository, queryRepository, postRepository, exclusionRepository,
                userRepository, topics, discovery, new AdminAuditService(auditRepository, userRepository),
                new ExternalBlogNotifier(notificationRepository, userRepository), events,
                new TransactionTemplate(transactionManager), clock);
        server.respond("/a.xml", 200, RSS, rss("Blog A", server.uri("/").toString(), "about",
                new String[] {"g1", "P1", server.uri("/a/1").toString(), null, "body"}));
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String url(String name) {
        return server.uri("/" + name + ".xml").toString();
    }

    private List<AdminAuditLog> audits(String action) {
        return em.createQuery("select a from AdminAuditLog a where a.action = :action order by a.id",
                AdminAuditLog.class).setParameter("action", action).getResultList();
    }

    private List<Notification> notifications(User user) {
        return em.createQuery("select n from Notification n where n.user.id = :userId order by n.id",
                Notification.class).setParameter("userId", user.getId()).getResultList();
    }

    @Test
    void createsActiveAdminDirectRegistration() {
        AdminExternalBlogResponse created = service.create(admin.getId(), url("a"), topic.getId(), " recommended ",
                IP);

        assertThat(created.base().status()).isEqualTo(ExternalBlogStatus.ACTIVE);
        assertThat(created.base().registrationType()).isEqualTo(RegistrationType.ADMIN_DIRECT);
        assertThat(created.base().title()).isEqualTo("Blog A");
        assertThat(created.member()).isNull();
        assertThat(created.registrationBasis()).isEqualTo("recommended");
        assertThat(created.reviewedBy().userId()).isEqualTo(admin.getId());
        assertThat(created.reviewedAt()).isEqualTo(clock.instant());
        assertThat(created.nextFetchAt()).isEqualTo(clock.instant());
        AdminAuditLog log = audits(AuditActions.EXTERNAL_BLOG_CREATE).getFirst();
        assertThat(log.getTargetType()).isEqualTo(AuditActions.TARGET_EXTERNAL_BLOG);
        assertThat(log.getTargetId()).isEqualTo(created.base().id());
        assertThat(log.getAfter()).containsEntry("feedUrl", url("a")).containsEntry("status", "ACTIVE");
        assertThat(log.getReason()).isEqualTo("recommended");
        assertThat(log.getRequestIp()).isEqualTo(IP);
    }

    @Test
    void createValidatesInput() {
        BusinessException basis = catchThrowableOfType(BusinessException.class,
                () -> service.create(admin.getId(), url("a"), topic.getId(), "  ", IP));
        assertThat(basis.fieldErrors()).singleElement().satisfies(fe -> {
            assertThat(fe.field()).isEqualTo("registrationBasis");
            assertThat(fe.code()).isEqualTo("REQUIRED");
        });
        BusinessException tooLong = catchThrowableOfType(BusinessException.class,
                () -> service.create(admin.getId(), url("a"), topic.getId(), "x".repeat(501), IP));
        assertThat(tooLong.fieldErrors()).singleElement().satisfies(fe -> assertThat(fe.code()).isEqualTo("TOO_LONG"));
        BusinessException noTopic = catchThrowableOfType(BusinessException.class,
                () -> service.create(admin.getId(), url("a"), null, "basis", IP));
        assertThat(noTopic.fieldErrors()).singleElement()
                .satisfies(fe -> assertThat(fe.field()).isEqualTo("defaultTopicId"));
        BusinessException majorTopic = catchThrowableOfType(BusinessException.class,
                () -> service.create(admin.getId(), url("a"), major.getId(), "basis", IP));
        assertThat(majorTopic.errorCode()).isEqualTo(ErrorCode.TOPIC_NOT_SELECTABLE);

        x.blog(member, url("a"), topic, ExternalBlogStatus.PENDING);
        BusinessException duplicate = catchThrowableOfType(BusinessException.class,
                () -> service.create(admin.getId(), url("a"), topic.getId(), "basis", IP));
        assertThat(duplicate.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_ALREADY_REGISTERED);
        assertThat(duplicate.params()).containsEntry("status", "PENDING");
        assertThat(audits(AuditActions.EXTERNAL_BLOG_CREATE)).isEmpty();
    }

    @Test
    void approvesPendingAndNotifies() {
        ExternalBlog pending = x.blog(member, url("a"), topic, ExternalBlogStatus.PENDING);

        AdminExternalBlogResponse approved = service.approve(admin.getId(), pending.getId(), IP);

        assertThat(approved.base().status()).isEqualTo(ExternalBlogStatus.ACTIVE);
        assertThat(approved.nextFetchAt()).isEqualTo(clock.instant());
        assertThat(approved.reviewedBy().userId()).isEqualTo(admin.getId());
        assertThat(approved.member().userId()).isEqualTo(member.getId());
        Notification n = notifications(member).getFirst();
        assertThat(n.getType()).isEqualTo(NotificationType.EXTERNAL_BLOG_APPROVED);
        assertThat(n.getTargetId()).isEqualTo(pending.getId());
        assertThat(n.getParams()).containsEntry("externalBlogTitle", pending.getTitle());
        AdminAuditLog log = audits(AuditActions.EXTERNAL_BLOG_APPROVE).getFirst();
        assertThat(log.getBefore()).isEqualTo(Map.of("status", "PENDING"));
        assertThat(log.getAfter()).isEqualTo(Map.of("status", "ACTIVE"));

        BusinessException again = catchThrowableOfType(BusinessException.class,
                () -> service.approve(admin.getId(), pending.getId(), IP));
        assertThat(again.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT);
        assertThat(again.params()).containsEntry("status", "ACTIVE");
        BusinessException missing = catchThrowableOfType(BusinessException.class,
                () -> service.approve(admin.getId(), 999_999L, IP));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_NOT_FOUND);
    }

    @Test
    void rejectRequiresReasonAndUsesHostWhenUntitled() {
        ExternalBlog pending = ExternalBlog.memberRequest(member, "https://untitled.example/feed",
                net.java21.blog.backend.external.feed.FeedUrlNormalizer.hash("https://untitled.example/feed"), topic);
        em.persist(pending);

        BusinessException noReason = catchThrowableOfType(BusinessException.class,
                () -> service.reject(admin.getId(), pending.getId(), " ", IP));
        assertThat(noReason.fieldErrors()).singleElement().satisfies(fe -> assertThat(fe.field()).isEqualTo("reason"));

        AdminExternalBlogResponse rejected = service.reject(admin.getId(), pending.getId(), "off topic", IP);

        assertThat(rejected.base().status()).isEqualTo(ExternalBlogStatus.REJECTED);
        assertThat(rejected.base().rejectReason()).isEqualTo("off topic");
        assertThat(rejected.nextFetchAt()).isNull();
        Notification n = notifications(member).getFirst();
        assertThat(n.getType()).isEqualTo(NotificationType.EXTERNAL_BLOG_REJECTED);
        assertThat(n.getParams()).containsEntry("externalBlogTitle", "untitled.example")
                .containsEntry("reason", "off topic");
        assertThat(audits(AuditActions.EXTERNAL_BLOG_REJECT).getFirst().getReason()).isEqualTo("off topic");
        BusinessException again = catchThrowableOfType(BusinessException.class,
                () -> service.reject(admin.getId(), pending.getId(), "again", IP));
        assertThat(again.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT);
    }

    @Test
    void changesDefaultTopicWithAudit() {
        ExternalBlog blog = x.blog(member, url("a"), topic, ExternalBlogStatus.ACTIVE);

        AdminExternalBlogResponse changed = service.updateDefaultTopic(admin.getId(), blog.getId(), other.getId(), IP);

        assertThat(changed.base().defaultTopicId()).isEqualTo(other.getId());
        AdminAuditLog log = audits(AuditActions.EXTERNAL_BLOG_UPDATE).getFirst();
        assertThat(log.getBefore()).isEqualTo(Map.of("defaultTopicId", topic.getId()));
        assertThat(log.getAfter()).isEqualTo(Map.of("defaultTopicId", other.getId()));
        BusinessException none = catchThrowableOfType(BusinessException.class,
                () -> service.updateDefaultTopic(admin.getId(), blog.getId(), null, IP));
        assertThat(none.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        BusinessException majorTopic = catchThrowableOfType(BusinessException.class,
                () -> service.updateDefaultTopic(admin.getId(), blog.getId(), major.getId(), IP));
        assertThat(majorTopic.errorCode()).isEqualTo(ErrorCode.TOPIC_NOT_SELECTABLE);
    }

    @Test
    void approvalTakesOverKeptPostsOfReleasedRegistration() {
        ExternalBlog released = x.blog(member, url("a"), topic, ExternalBlogStatus.ACTIVE);
        ExternalPost kept = x.post(released, "kept", topic, null);
        ExternalPost removed = x.removed(released, "removed", topic, RemovedReason.ADMIN);
        ExternalPost withdrawn = x.removed(released, "withdrawn", topic, RemovedReason.MEMBER_WITHDRAWN);
        ExternalFixtures.moveTo(released, ExternalBlogStatus.RELEASED);
        em.flush();
        ExternalBlog otherFeed = x.blog(member, "https://elsewhere.example/feed", topic, ExternalBlogStatus.RELEASED);
        ExternalPost elsewhere = x.post(otherFeed, "elsewhere", topic, null);
        User second = f.user("second");
        ExternalBlog again = x.blog(second, url("a"), topic, ExternalBlogStatus.PENDING);
        em.flush();

        service.approve(admin.getId(), again.getId(), IP);

        assertThat(blogOf(kept)).isEqualTo(again.getId());
        assertThat(blogOf(removed)).isEqualTo(again.getId());
        assertThat(blogOf(withdrawn)).isEqualTo(released.getId());
        assertThat(blogOf(elsewhere)).isEqualTo(otherFeed.getId());
        verify(events).publishEvent(any(PortalChangedEvent.class));
        assertThat(postRepository.findById(removed.getId()).orElseThrow().getStatus())
                .isEqualTo(ExternalPostStatus.REMOVED);
    }

    @Test
    void adminDirectRegistrationTakesOverKeptPosts() {
        ExternalBlog released = x.blog(member, url("a"), topic, ExternalBlogStatus.ACTIVE);
        ExternalPost kept = x.post(released, "kept", topic, null);
        ExternalFixtures.moveTo(released, ExternalBlogStatus.RELEASED);
        em.flush();

        AdminExternalBlogResponse created = service.create(admin.getId(), url("a"), topic.getId(), "basis", IP);

        assertThat(blogOf(kept)).isEqualTo(created.base().id());
        assertThat(created.base().postCount()).isEqualTo(1);
    }

    @Test
    void approvalWithoutKeptPostsDoesNotInvalidatePortal() {
        ExternalBlog pending = x.blog(member, url("a"), topic, ExternalBlogStatus.PENDING);
        service.approve(admin.getId(), pending.getId(), IP);
        verify(events, never()).publishEvent(any(PortalChangedEvent.class));
    }

    @Test
    void listsAndReadsWithPostsAndExclusions() {
        ExternalBlog pending = x.blog(member, url("a"), topic, ExternalBlogStatus.PENDING);
        ExternalBlog active = x.blog(null, "https://direct.example/feed", topic, ExternalBlogStatus.ACTIVE);
        ExternalPost post = x.post(active, "first", topic, null);
        x.post(active, "second", topic, null);
        em.persist(new PortalExclusion(post, "spam", admin));
        em.flush();

        assertThat(service.list(null, null, PageRequest.of(0, 20)).getContent())
                .extracting(r -> r.base().id()).first().isEqualTo(pending.getId());
        assertThat(service.list(ExternalBlogStatus.ACTIVE, "direct", PageRequest.of(0, 20)).getContent())
                .extracting(r -> r.base().id()).containsExactly(active.getId());
        BusinessException shortQ = catchThrowableOfType(BusinessException.class,
                () -> service.list(null, "d", PageRequest.of(0, 20)));
        assertThat(shortQ.fieldErrors()).singleElement().satisfies(fe -> assertThat(fe.code()).isEqualTo("TOO_SHORT"));
        BusinessException longQ = catchThrowableOfType(BusinessException.class,
                () -> service.list(null, "d".repeat(101), PageRequest.of(0, 20)));
        assertThat(longQ.fieldErrors()).singleElement().satisfies(fe -> assertThat(fe.code()).isEqualTo("TOO_LONG"));

        assertThat(service.get(active.getId()).base().postCount()).isEqualTo(2);
        List<AdminExternalPostResponse> posts = service.posts(active.getId(), null, PageRequest.of(0, 20))
                .getContent();
        assertThat(posts).hasSize(2);
        assertThat(posts).filteredOn(p -> p.base().id() == post.getId()).singleElement()
                .satisfies(p -> assertThat(p.excluded().reason()).isEqualTo("spam"));
        BusinessException missing = catchThrowableOfType(BusinessException.class, () -> service.get(999_999L));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_NOT_FOUND);
        BusinessException missingPosts = catchThrowableOfType(BusinessException.class,
                () -> service.posts(999_999L, null, PageRequest.of(0, 20)));
        assertThat(missingPosts.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_NOT_FOUND);
    }

    private Long blogOf(ExternalPost post) {
        em.clear();
        return postRepository.findById(post.getId()).orElseThrow().getExternalBlog().getId();
    }
}
