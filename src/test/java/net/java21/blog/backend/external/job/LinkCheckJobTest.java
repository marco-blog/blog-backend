package net.java21.blog.backend.external.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.feed.FeedItem;
import net.java21.blog.backend.external.feed.FeedUrlNormalizer;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.ExternalTestKit;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.StubHttpServer;
import net.java21.blog.backend.topic.domain.Topic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 007 T048: 원문 링크 점검 — HEAD 200 유지, 404·410 내림({@code LINK_BROKEN}), 405 → {@code GET Range: bytes=0-0}, 500·시간 초과·403·
 * 내부망 리다이렉트 유지, 이름 해석 실패 내림, 모든 경우 {@code link_checked_at}, 7일 안에 점검한 글 제외, 한 번에
 * {@code link-check-batch}개, 같은 호스트 1초 간격, 내린 글이 있으면 포털 캐시 무효화 (FR-117, research E12).
 */
@JpaRepositoryTest
class LinkCheckJobTest {

    private static final Instant NOW = Instant.parse("2026-10-12T04:30:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private StubHttpServer server;
    private MutableClock clock;
    private ApplicationEventPublisher events;
    private final List<Duration> sleeps = new ArrayList<>();
    private ExternalBlog blog;
    private Topic topic;
    private int seq;

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
        clock = new MutableClock(NOW);
        events = mock(ApplicationEventPublisher.class);
        JpaFixtures fx = new JpaFixtures(em);
        topic = fx.topic(fx.topic(null, "knowledge", 0), "it-internet", 0);
        blog = new ExternalFixtures(em).blog(fx.user("m"), topic, ExternalBlogStatus.ACTIVE);
        server.respondBytes("/ok", 200, null, null, Map.of());
        server.respondBytes("/gone404", 404, null, null, Map.of());
        server.respondBytes("/gone410", 410, null, null, Map.of());
        server.respondBytes("/error500", 500, null, null, Map.of());
        server.respondBytes("/forbidden", 403, null, null, Map.of());
        server.respond("/slow", 200, "text/plain", "late", Duration.ofSeconds(2));
        server.redirect("/to-internal", 302, "http://internal.test/x");
        server.handle("/no-head", exchange -> {
            boolean head = "HEAD".equals(exchange.getRequestMethod());
            exchange.sendResponseHeaders(head ? 405 : 206, -1);
            exchange.close();
        });
        server.handle("/no-head-gone", exchange -> {
            boolean head = "HEAD".equals(exchange.getRequestMethod());
            exchange.sendResponseHeaders(head ? 501 : 404, -1);
            exchange.close();
        });
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private LinkCheckJob job(TaskExecutor executor, String batch) {
        return new LinkCheckJob(postRepository, ExternalTestKit.fetcher(server, Duration.ofMillis(500)), executor,
                new TransactionTemplate(transactionManager), events,
                ExternalTestKit.properties("link-check-batch", batch), clock, sleeps::add);
    }

    private ExternalPost post(String link, Instant checkedAt) {
        int n = ++seq;
        FeedItem item = new FeedItem("g" + n, link, "t" + n, "s", null, NOW.minus(Duration.ofDays(1)), List.of());
        ExternalPost post = new ExternalPost(blog, item, FeedUrlNormalizer.sha256("g" + n),
                FeedUrlNormalizer.hash(link), topic, TopicSource.DEFAULT, NOW.minus(Duration.ofDays(1)));
        em.persist(post);
        if (checkedAt != null) {
            post.linkChecked(checkedAt);
        }
        return post;
    }

    private String stub(String path) {
        return server.uri(path).toString();
    }

    private ExternalPost reload(ExternalPost post) {
        em.flush();
        em.clear();
        return em.find(ExternalPost.class, post.getId());
    }

    @Test
    void removesOnlyCertainlyBrokenLinksAndStampsEveryCheck() {
        ExternalPost ok = post(stub("/ok"), null);
        ExternalPost gone404 = post(stub("/gone404"), null);
        ExternalPost gone410 = post(stub("/gone410"), null);
        ExternalPost noHead = post(stub("/no-head"), null);
        ExternalPost noHeadGone = post(stub("/no-head-gone"), null);
        ExternalPost error = post(stub("/error500"), null);
        ExternalPost forbidden = post(stub("/forbidden"), null);
        ExternalPost slow = post(stub("/slow"), null);
        ExternalPost internal = post(stub("/to-internal"), null);
        ExternalPost dns = post("http://no-such-host.invalid/post", null);
        em.flush();

        LinkCheckJob.Summary summary = job(new SyncTaskExecutor(), "500").runOnce();

        assertThat(summary.selected()).isEqualTo(10);
        assertThat(summary.removed()).isEqualTo(4);
        for (ExternalPost kept : List.of(ok, noHead, error, forbidden, slow, internal)) {
            ExternalPost reloaded = reload(kept);
            assertThat(reloaded.getStatus()).as(kept.getLink()).isEqualTo(ExternalPostStatus.ACTIVE);
            assertThat(reloaded.getLinkCheckedAt()).isEqualTo(NOW);
        }
        for (ExternalPost removed : List.of(gone404, gone410, noHeadGone, dns)) {
            ExternalPost reloaded = reload(removed);
            assertThat(reloaded.getStatus()).as(removed.getLink()).isEqualTo(ExternalPostStatus.REMOVED);
            assertThat(reloaded.getRemovedReason()).isEqualTo(RemovedReason.LINK_BROKEN);
            assertThat(reloaded.getLinkCheckedAt()).isEqualTo(NOW);
        }
        assertThat(server.requests()).filteredOn(r -> r.path().equals("/no-head") && r.method().equals("GET"))
                .singleElement().satisfies(r -> assertThat(r.header("Range")).isEqualTo("bytes=0-0"));
        verify(events, times(4)).publishEvent(new PortalChangedEvent("external-link-check"));
        // 같은 호스트(127.0.0.1) 9개 사이 8번 1초 쉼, 다른 호스트는 쉬지 않음
        assertThat(sleeps).hasSize(8).containsOnly(Duration.ofSeconds(1));
    }

    @Test
    void recentlyCheckedRemovedAndOverBatchAreSkippedOldestFirst() {
        ExternalPost recent = post(stub("/gone404"), NOW.minus(Duration.ofDays(6)));
        ExternalPost oldest = post(stub("/ok") + "?a", NOW.minus(Duration.ofDays(30)));
        ExternalPost never = post(stub("/ok") + "?b", null);
        ExternalPost older = post(stub("/ok") + "?c", NOW.minus(Duration.ofDays(8)));
        ExternalPost removed = post(stub("/ok") + "?d", null);
        removed.remove(RemovedReason.ADMIN);
        em.flush();

        LinkCheckJob.Summary summary = job(new SyncTaskExecutor(), "2").runOnce();

        assertThat(summary.selected()).isEqualTo(2);
        assertThat(summary.removed()).isZero();
        assertThat(reload(never).getLinkCheckedAt()).isEqualTo(NOW);
        assertThat(reload(oldest).getLinkCheckedAt()).isEqualTo(NOW);
        assertThat(reload(older).getLinkCheckedAt()).isEqualTo(NOW.minus(Duration.ofDays(8)));
        assertThat(reload(recent).getStatus()).isEqualTo(ExternalPostStatus.ACTIVE);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void fullQueueRetriesThenGivesUp() {
        post(stub("/ok"), null);
        em.flush();
        TaskExecutor full = task -> {
            throw new TaskRejectedException("full");
        };

        LinkCheckJob.Summary summary = job(full, "500").runOnce();

        assertThat(summary.selected()).isEqualTo(1);
        assertThat(sleeps).hasSize(LinkCheckJob.RESUBMIT_TRIES).containsOnly(LinkCheckJob.RESUBMIT_WAIT);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void hostOfMalformedLinkIsEmpty() {
        assertThat(LinkCheckJob.host("http://Example.COM/x")).isEqualTo("example.com");
        assertThat(LinkCheckJob.host("::bad")).isEmpty();
        assertThat(job(new SyncTaskExecutor(), "1").isBroken("::bad")).isFalse();
    }
}
