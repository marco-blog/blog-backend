package net.java21.blog.backend.external.verify;

import static net.java21.blog.backend.support.ExternalTestKit.rss;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.feed.FeedDiscovery;
import net.java21.blog.backend.external.feed.FeedParser;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalBlogVerificationRepository;
import net.java21.blog.backend.report.ReportsProperties;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.spam.RateLimitPolicy;
import net.java21.blog.backend.spam.RateLimiter;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.ExternalTestKit;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.StubHttpServer;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.trackback.TrackbackProperties;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 007 T024: 소유 인증 코드 발급·확인(US1 AS2·AS3, FR-110, research E8). */
@JpaRepositoryTest
class VerificationServiceTest {

    private static final String RSS = "application/rss+xml; charset=UTF-8";

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogVerificationRepository repository;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private StubHttpServer server;
    private MutableClock clock;
    private VerificationService service;
    private User member;
    private User other;
    private Topic topic;

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
        clock = new MutableClock(JpaFixtures.T0);
        JpaFixtures f = new JpaFixtures(em);
        member = f.user("member");
        other = f.user("other");
        topic = f.topic(f.topic(null, "knowledge", 1), "it-internet", 1);
        service = service(ExternalTestKit.properties("verify-checks-per-hour", "3"));
    }

    private VerificationService service(ExternalFeedProperties props) {
        FeedParser parser = new FeedParser();
        FeedDiscovery discovery = new FeedDiscovery(ExternalTestKit.fetcher(server, Duration.ofMillis(800)), parser,
                clock);
        RateLimitPolicy policy = new RateLimitPolicy(new RateLimiter(() -> clock.instant().toEpochMilli() * 1_000_000),
                mock(SystemSettingsService.class), ReportsProperties.defaults(), TrackbackProperties.defaults(), props);
        return new VerificationService(repository, blogRepository, userRepository, new VerificationCodeGenerator(),
                discovery, ExternalTestKit.fetcher(server, Duration.ofMillis(800)), parser, policy, props,
                new TransactionTemplate(transactionManager), clock);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String feedUrl() {
        return server.uri("/feed.xml").toString();
    }

    private void feed(String description, String itemBody) {
        server.respond("/feed.xml", 200, RSS, rss("Dev", server.uri("/").toString(), description,
                new String[] {"g1", "Post", server.uri("/p/1").toString(), null, itemBody}));
    }

    @Test
    void issuesCodeOnceAndReusesWhileValid() {
        VerificationService.Issued first = service.issue(member.getId(), feedUrl());
        assertThat(first.created()).isTrue();
        assertThat(first.verification().code()).matches("java21-verify-[0-9A-Za-z]{12}");
        assertThat(first.verification().expiresAt()).isEqualTo(JpaFixtures.T0.plus(Duration.ofHours(24)));
        assertThat(VerificationCodeGenerator.isCode(first.verification().code())).isTrue();

        VerificationService.Issued again = service.issue(member.getId(), feedUrl());
        assertThat(again.created()).isFalse();
        assertThat(again.verification().id()).isEqualTo(first.verification().id());

        clock.advance(Duration.ofHours(25));
        assertThat(service.issue(member.getId(), feedUrl()).created()).isTrue();
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void findsCodeInChannelDescription() {
        VerificationResponse v = service.issue(member.getId(), feedUrl()).verification();
        feed("about " + v.code(), "body");

        VerificationResponse checked = service.check(member.getId(), v.id(), null);

        assertThat(checked.verifiedAt()).isEqualTo(JpaFixtures.T0);
        assertThat(server.requests()).extracting(StubHttpServer.Recorded::path).containsExactly("/feed.xml");
        // 이미 인증됐으면 외부 요청 없이 그대로
        server.clearRequests();
        assertThat(service.check(member.getId(), v.id(), null).verifiedAt()).isNotNull();
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void findsCodeInItemBody() {
        VerificationResponse v = service.issue(member.getId(), feedUrl()).verification();
        feed("about", "<p>my code: " + v.code() + "</p>");
        assertThat(service.check(member.getId(), v.id(), null).verifiedAt()).isNotNull();
    }

    @Test
    void findsCodeInSiteTextOrMeta() {
        VerificationResponse v = service.issue(member.getId(), feedUrl()).verification();
        feed("about", "body");
        server.respond("/", 200, "text/html", "<html><head><meta name=\"description\" content=\"" + v.code()
                + "\"></head><body>hello</body></html>");
        assertThat(service.check(member.getId(), v.id(), null).verifiedAt()).isNotNull();

        VerificationResponse w = service.issue(other.getId(), feedUrl()).verification();
        server.respond("/", 200, "text/html", "<html><body><p>intro " + w.code() + "</p></body></html>");
        assertThat(service.check(other.getId(), w.id(), null).verifiedAt()).isNotNull();
    }

    @Test
    void notFoundReportsCheckedPlacesAndFailures() {
        VerificationResponse v = service.issue(member.getId(), feedUrl()).verification();
        feed("about", "body");
        server.respond("/", 200, "text/html", "<html><body>x</body></html>", Duration.ofSeconds(3));

        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.check(member.getId(), v.id(), null));

        assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_VERIFICATION_CODE_NOT_FOUND);
        assertThat(e.params()).containsEntry("checked", List.of("FEED", "SITE"))
                .containsEntry("failures", Map.of("SITE", "TIMEOUT"));
    }

    @Test
    void expiredCodeIsRefused() {
        VerificationResponse v = service.issue(member.getId(), feedUrl()).verification();
        clock.advance(Duration.ofHours(24));
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.check(member.getId(), v.id(), null));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_VERIFICATION_EXPIRED);
        assertThat(e.params()).containsKey("expiredAt");
    }

    @Test
    void someoneElsesVerificationIsHidden() {
        VerificationResponse v = service.issue(member.getId(), feedUrl()).verification();
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.check(other.getId(), v.id(), null));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.EXTERNAL_VERIFICATION_NOT_FOUND);
        BusinessException missing = catchThrowableOfType(BusinessException.class,
                () -> service.check(member.getId(), 999_999L, null));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.EXTERNAL_VERIFICATION_NOT_FOUND);
    }

    @Test
    void claimableRegistrationOfAnotherMember() {
        ExternalBlog recommended = new ExternalFixtures(em).blog(other, feedUrl(), topic, ExternalBlogStatus.ACTIVE);
        VerificationResponse v = service.issue(member.getId(), feedUrl()).verification();
        feed(v.code(), "body");

        assertThat(service.check(member.getId(), v.id(), null).claimableExternalBlogId())
                .isEqualTo(recommended.getId());
    }

    @Test
    void hourlyCheckLimit() {
        VerificationResponse v = service.issue(member.getId(), feedUrl()).verification();
        feed("about", "body");
        server.respond("/", 200, "text/html", "<html><body>x</body></html>");
        for (int i = 0; i < 3; i++) {
            catchThrowableOfType(BusinessException.class, () -> service.check(member.getId(), v.id(), null));
        }
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.check(member.getId(), v.id(), null));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    void forgottenFeedUrlComesFromHintWithSameHash() {
        VerificationResponse v = service.issue(member.getId(), feedUrl()).verification();
        VerificationService fresh = service(ExternalTestKit.properties());
        feed(v.code(), "body");

        BusinessException required = catchThrowableOfType(BusinessException.class,
                () -> fresh.check(member.getId(), v.id(), null));
        assertThat(required.fieldErrors()).extracting(f -> f.code()).containsExactly("REQUIRED");
        BusinessException other = catchThrowableOfType(BusinessException.class,
                () -> fresh.check(member.getId(), v.id(), server.uri("/other.xml").toString()));
        assertThat(other.fieldErrors()).extracting(f -> f.code()).containsExactly("INVALID");
        assertThat(fresh.check(member.getId(), v.id(), feedUrl()).feedUrl()).isEqualTo(feedUrl());
    }

    @Test
    void forgottenFeedUrlComesFromRegistration() {
        VerificationResponse v = service.issue(member.getId(), feedUrl()).verification();
        new ExternalFixtures(em).blog(other, feedUrl(), topic, ExternalBlogStatus.REJECTED);
        VerificationService fresh = service(ExternalTestKit.properties());
        feed(v.code(), "body");
        assertThat(fresh.check(member.getId(), v.id(), null).verifiedAt()).isNotNull();
    }
}
