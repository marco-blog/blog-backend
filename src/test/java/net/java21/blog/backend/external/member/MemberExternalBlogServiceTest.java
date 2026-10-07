package net.java21.blog.backend.external.member;

import static net.java21.blog.backend.support.ExternalTestKit.rss;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalBlogVerification;
import net.java21.blog.backend.external.domain.RegistrationType;
import net.java21.blog.backend.external.dto.MyExternalBlogResponse;
import net.java21.blog.backend.external.dto.MyExternalPostResponse;
import net.java21.blog.backend.external.feed.FeedDiscovery;
import net.java21.blog.backend.external.feed.FeedParser;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.external.repository.ExternalBlogQueryRepository;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalBlogVerificationRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.external.thumbnail.ThumbnailBackfillRequested;
import net.java21.blog.backend.external.verify.VerificationCodeGenerator;
import net.java21.blog.backend.portal.PortalProperties;
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
 * 007 T025: 회원 신청·넘겨받기·조회(US1 AS4·AS5·AS8, FR-111, FR-112, FR-129). 저장소는 H2, 피드는 {@link StubHttpServer}. 동시 신청은
 * {@link MemberExternalBlogLimitIntegrationTest}.
 */
@JpaRepositoryTest
@Import(ExternalBlogQueryRepository.class)
class MemberExternalBlogServiceTest {

    private static final String RSS = "application/rss+xml; charset=UTF-8";

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private ExternalBlogQueryRepository queryRepository;
    @Autowired
    private ExternalBlogVerificationRepository verificationRepository;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TopicRepository topicRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private StubHttpServer server;
    private MutableClock clock;
    private ApplicationEventPublisher events;
    private MemberExternalBlogService service;
    private JpaFixtures f;
    private ExternalFixtures x;
    private User member;
    private User other;
    private Topic major;
    private Topic topic;

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
        clock = new MutableClock(JpaFixtures.T0);
        f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        member = f.user("member");
        other = f.user("other");
        major = f.topic(null, "knowledge", 1);
        topic = f.topic(major, "it-internet", 1);
        events = mock(ApplicationEventPublisher.class);
        TopicService topics = new TopicService(topicRepository, null, null, null, null, null,
                PortalProperties.defaults());
        FeedDiscovery discovery = new FeedDiscovery(ExternalTestKit.fetcher(server), new FeedParser(), clock);
        service = new MemberExternalBlogService(blogRepository, queryRepository, verificationRepository,
                postRepository, userRepository, topics, discovery, ExternalTestKit.properties(), events,
                new TransactionTemplate(transactionManager), clock);
        for (String name : List.of("a", "b", "c", "d", "e")) {
            server.respond("/" + name + ".xml", 200, RSS, rss("Blog " + name, server.uri("/").toString(), "about",
                    new String[] {"g1", "P1", server.uri("/" + name + "/1").toString(), null, "body"}));
        }
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String url(String name) {
        return server.uri("/" + name + ".xml").toString();
    }

    @Test
    void createsPendingMemberRequest() {
        MyExternalBlogResponse created = service.create(member.getId(), url("a"), topic.getId(), null);

        assertThat(created.status()).isEqualTo(ExternalBlogStatus.PENDING);
        assertThat(created.registrationType()).isEqualTo(RegistrationType.MEMBER_REQUEST);
        assertThat(created.title()).isEqualTo("Blog a");
        assertThat(created.ownershipVerified()).isFalse();
        assertThat(created.defaultTopicId()).isEqualTo(topic.getId());
        ExternalBlog saved = blogRepository.findById(created.id()).orElseThrow();
        assertThat(saved.getMember().getId()).isEqualTo(member.getId());
        assertThat(saved.getFeedUrlHash()).isEqualTo(FeedUrlNormalizer.hash(url("a")));
        assertThat(service.list(member.getId())).extracting(MyExternalBlogResponse::id).containsExactly(created.id());
        assertThat(service.get(member.getId(), created.id()).feedUrl()).isEqualTo(url("a"));
    }

    @Test
    void unreadableFeedIsRefused() {
        server.respond("/bad.xml", 200, "application/rss+xml", "<rss>");
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.create(member.getId(), url("bad"), topic.getId(), null));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_FEED_UNREADABLE);
        assertThat(e.params()).containsEntry("result", "PARSE_ERROR");
        BusinessException missing = catchThrowableOfType(BusinessException.class,
                () -> service.create(member.getId(), url("missing"), topic.getId(), null));
        assertThat(missing.params()).containsEntry("result", "HTTP_ERROR").containsEntry("httpStatus", 404);
    }

    @Test
    void limitCountsHeldButNotRejectedOrReleased() {
        service.create(member.getId(), url("a"), topic.getId(), null);
        service.create(member.getId(), url("b"), topic.getId(), null);
        ExternalBlog third = x.blog(member, "https://blocked.example/feed", topic, ExternalBlogStatus.BLOCKED);
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.create(member.getId(), url("c"), topic.getId(), null));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_LIMIT_EXCEEDED);
        assertThat(e.params()).containsEntry("max", 3);

        x.blog(member, "https://rejected.example/feed", topic, ExternalBlogStatus.REJECTED);
        x.blog(member, "https://released.example/feed", topic, ExternalBlogStatus.RELEASED);
        em.remove(third);
        em.flush();
        service.create(member.getId(), url("c"), topic.getId(), null);
    }

    @Test
    void duplicateFeedIsRefusedWithClaimable() {
        ExternalBlog existing = x.blog(other, url("a"), topic, ExternalBlogStatus.ACTIVE);
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.create(member.getId(), url("a"), topic.getId(), null));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_ALREADY_REGISTERED);
        assertThat(e.params()).containsEntry("externalBlogId", existing.getId()).containsEntry("status", "ACTIVE")
                .containsEntry("claimable", true);

        x.blog(other, url("b"), topic, ExternalBlogStatus.BLOCKED);
        BusinessException blocked = catchThrowableOfType(BusinessException.class,
                () -> service.create(member.getId(), url("b"), topic.getId(), null));
        assertThat(blocked.params()).containsEntry("claimable", false);
    }

    @Test
    void rejectedOrReleasedFeedCanBeRequestedAgain() {
        x.blog(other, url("a"), topic, ExternalBlogStatus.REJECTED);
        x.blog(other, url("b"), topic, ExternalBlogStatus.RELEASED);
        service.create(member.getId(), url("a"), topic.getId(), null);
        service.create(member.getId(), url("b"), topic.getId(), null);
        assertThat(service.list(member.getId())).hasSize(2);
    }

    @Test
    void verificationMarksOwnershipAndLinks() {
        ExternalBlogVerification v = verified(member, url("a"));
        MyExternalBlogResponse created = service.create(member.getId(), url("a"), topic.getId(), v.getId());
        assertThat(created.ownershipVerified()).isTrue();
        assertThat(verificationRepository.findById(v.getId()).orElseThrow().getExternalBlog().getId())
                .isEqualTo(created.id());

        ExternalBlogVerification others = verified(other, url("b"));
        assertInvalidVerification(() -> service.create(member.getId(), url("b"), topic.getId(), others.getId()));
        ExternalBlogVerification otherFeed = verified(member, url("c"));
        assertInvalidVerification(() -> service.create(member.getId(), url("b"), topic.getId(), otherFeed.getId()));
        ExternalBlogVerification unverified = new ExternalBlogVerification(member, FeedUrlNormalizer.hash(url("b")),
                "java21-verify-AAAAAAAAAAAA", JpaFixtures.T0.plusSeconds(3600));
        em.persist(unverified);
        assertInvalidVerification(() -> service.create(member.getId(), url("b"), topic.getId(), unverified.getId()));
        clock.advance(java.time.Duration.ofHours(25));
        ExternalBlogVerification old = verificationRepository.findById(otherFeed.getId()).orElseThrow();
        assertInvalidVerification(() -> service.create(member.getId(), url("c"), topic.getId(), old.getId()));
    }

    @Test
    void topicMustBeSelectable() {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.create(member.getId(), url("a"), major.getId(), null));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.TOPIC_NOT_SELECTABLE);
        BusinessException none = catchThrowableOfType(BusinessException.class,
                () -> service.create(member.getId(), url("a"), null, null));
        assertThat(none.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        BusinessException missing = catchThrowableOfType(BusinessException.class,
                () -> service.create(member.getId(), url("a"), 999_999L, null));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.TOPIC_NOT_FOUND);
    }

    @Test
    void claimTakesOverUnverifiedRegistrations() {
        ExternalBlog adminDirect = x.blog(null, url("a"), topic, ExternalBlogStatus.ACTIVE);
        ExternalBlog recommended = x.blog(other, url("b"), topic, ExternalBlogStatus.PENDING);

        MyExternalBlogResponse a = service.claim(member.getId(), adminDirect.getId(), verified(member, url("a")).getId());
        MyExternalBlogResponse b = service.claim(member.getId(), recommended.getId(), verified(member, url("b")).getId());

        assertThat(a.ownershipVerified()).isTrue();
        assertThat(b.ownershipVerified()).isTrue();
        assertThat(blogRepository.findById(recommended.getId()).orElseThrow().getMember().getId())
                .isEqualTo(member.getId());
        verify(events).publishEvent(new ThumbnailBackfillRequested(adminDirect.getId()));
        // 이미 인증된 주인이면 그대로
        ExternalBlogVerification again = verified(member, url("a"));
        assertThat(service.claim(member.getId(), adminDirect.getId(), again.getId()).id()).isEqualTo(a.id());
    }

    @Test
    void claimRules() {
        ExternalBlog blocked = x.blog(other, url("a"), topic, ExternalBlogStatus.BLOCKED);
        ExternalBlog released = x.blog(other, url("b"), topic, ExternalBlogStatus.RELEASED);
        ExternalBlog active = x.blog(other, url("c"), topic, ExternalBlogStatus.ACTIVE);

        BusinessException e1 = catchThrowableOfType(BusinessException.class,
                () -> service.claim(member.getId(), blocked.getId(), verified(member, url("a")).getId()));
        assertThat(e1.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_STATE_CONFLICT);
        BusinessException e2 = catchThrowableOfType(BusinessException.class,
                () -> service.claim(member.getId(), released.getId(), verified(member, url("b")).getId()));
        assertThat(e2.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_NOT_FOUND);
        assertInvalidVerification(() -> service.claim(member.getId(), active.getId(), verified(other, url("c")).getId()));
        assertInvalidVerification(() -> service.claim(member.getId(), active.getId(), null));

        x.blog(member, "https://m1.example/feed", topic, ExternalBlogStatus.ACTIVE);
        x.blog(member, "https://m2.example/feed", topic, ExternalBlogStatus.ACTIVE);
        x.blog(member, "https://m3.example/feed", topic, ExternalBlogStatus.PENDING);
        BusinessException limit = catchThrowableOfType(BusinessException.class,
                () -> service.claim(member.getId(), active.getId(), verified(member, url("c")).getId()));
        assertThat(limit.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_LIMIT_EXCEEDED);
        verify(events, never()).publishEvent(any(ThumbnailBackfillRequested.class));
    }

    @Test
    void readsOnlyOwnRegistrations() {
        ExternalBlog mine = x.blog(member, url("a"), topic, ExternalBlogStatus.ACTIVE);
        ExternalBlog theirs = x.blog(other, url("b"), topic, ExternalBlogStatus.ACTIVE);
        x.post(mine, "kept", topic, null);
        x.removed(mine, "gone", topic, net.java21.blog.backend.external.domain.RemovedReason.ADMIN);

        assertThat(service.get(member.getId(), mine.getId()).postCount()).isEqualTo(1);
        assertThat(service.list(member.getId())).singleElement()
                .extracting(MyExternalBlogResponse::postCount).isEqualTo(1L);
        assertThat(service.posts(member.getId(), mine.getId(), PageRequest.of(0, 20)).getContent())
                .extracting(MyExternalPostResponse::title).containsExactlyInAnyOrder("kept", "gone");
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.get(member.getId(), theirs.getId()));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_NOT_FOUND);
        BusinessException p = catchThrowableOfType(BusinessException.class,
                () -> service.posts(member.getId(), theirs.getId(), PageRequest.of(0, 20)));
        assertThat(p.errorCode()).isEqualTo(ErrorCode.EXTERNAL_BLOG_NOT_FOUND);
    }

    private ExternalBlogVerification verified(User user, String feedUrl) {
        ExternalBlogVerification v = new ExternalBlogVerification(user, FeedUrlNormalizer.hash(feedUrl),
                new VerificationCodeGenerator().next(),
                clock.instant().plusSeconds(86400));
        v.verify(clock.instant());
        em.persist(v);
        return v;
    }

    private static void assertInvalidVerification(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        BusinessException e = catchThrowableOfType(BusinessException.class, call);
        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(e.fieldErrors()).singleElement().satisfies(fe -> assertThat(fe.field()).isEqualTo("verificationId"));
    }
}
