package net.java21.blog.backend.external.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.FetchResultCode;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.external.domain.TopicSource;
import net.java21.blog.backend.external.feed.FeedParser;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.external.thumbnail.ExternalThumbnailService;
import net.java21.blog.backend.notification.domain.Notification;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.notification.repository.NotificationRepository;
import net.java21.blog.backend.notification.service.ExternalBlogNotifier;
import net.java21.blog.backend.portal.event.PortalChangedEvent;
import net.java21.blog.backend.setting.service.SystemSettingsService;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.ExternalTestKit;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.StubHttpServer;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 007 T028: 피드 하나 수집(research E1·E5, FR-113, FR-115~117). 저장소는 H2, 피드는 {@link StubHttpServer}, 시각은
 * {@link MutableClock}. 시험 메서드의 트랜잭션 안에서 돌므로 각 수집 앞에서 영속성 컨텍스트를 비워 실제 쿼리를 센다.
 */
@JpaRepositoryTest
class FeedCollectorTest {

    private static final String RSS = "application/rss+xml; charset=UTF-8";
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);
    private static final Instant NOW = JpaFixtures.T0;

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalBlogRepository blogRepository;
    @Autowired
    private ExternalPostRepository postRepository;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private QueryCounter queryCounter;

    private StubHttpServer server;
    private MutableClock clock;
    private ApplicationEventPublisher events;
    private ExternalThumbnailService thumbnails;
    private FeedCollector collector;
    private ExternalFixtures x;
    private User member;
    private Topic topic;
    private Topic other;
    private final AtomicReference<String> feedBody = new AtomicReference<>();
    private final AtomicReference<String> etag = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        server = StubHttpServer.start();
        clock = new MutableClock(NOW);
        JpaFixtures f = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        member = f.user("member");
        Topic major = f.topic(null, "knowledge", 1);
        topic = f.topic(major, "it-internet", 1);
        other = f.topic(major, "science", 2);
        events = mock(ApplicationEventPublisher.class);
        thumbnails = mock(ExternalThumbnailService.class);
        SystemSettingsService settings = mock(SystemSettingsService.class);
        when(settings.externalFetchInterval()).thenReturn(Duration.ofMinutes(30));
        ExternalFeedProperties props = ExternalTestKit.properties("fetch-jitter", "0s");
        FeedStopNotifier stopNotifier = new FeedStopNotifier(props,
                new ExternalBlogNotifier(notificationRepository, userRepository));
        collector = new FeedCollector(blogRepository, ExternalTestKit.fetcher(server), new FeedParser(),
                new ExternalPostUpserter(postRepository, em), new DefaultTopicAssigner(), stopNotifier, thumbnails,
                settings, props, events, new TransactionTemplate(transactionManager), clock);
        server.handle("/feed.xml", exchange -> {
            String current = etag.get();
            String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (current != null && current.equals(inm)) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            byte[] body = feedBody.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", RSS);
            if (current != null) {
                exchange.getResponseHeaders().add("ETag", current);
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private String feedUrl() {
        return server.uri("/feed.xml").toString();
    }

    /** {@code [guid, title, link, publishedAt, description]}. guid가 null이면 guid 없음. */
    private static String[] item(String guid, String title, String link, Instant published, String description) {
        return new String[] {guid, title, link, published == null ? null : RFC_1123.format(published), description};
    }

    private void feed(String[]... items) {
        feedBody.set(ExternalTestKit.rss("Remote Blog", server.uri("/").toString(), "about", items));
    }

    private String link(String path) {
        return server.uri(path).toString();
    }

    private ExternalBlog activeBlog() {
        ExternalBlog blog = x.blog(member, feedUrl(), topic, ExternalBlogStatus.ACTIVE);
        em.flush();
        em.clear();
        return blog;
    }

    private FeedCollector.Outcome collect(ExternalBlog blog) {
        em.flush();
        em.clear();
        return collector.collect(blog.getId());
    }

    private List<ExternalPost> posts(ExternalBlog blog) {
        em.flush();
        em.clear();
        return em.createQuery("select p from ExternalPost p where p.externalBlog.id = :id order by p.id",
                ExternalPost.class).setParameter("id", blog.getId()).getResultList();
    }

    private ExternalBlog reload(ExternalBlog blog) {
        em.flush();
        em.clear();
        return blogRepository.findById(blog.getId()).orElseThrow();
    }

    @Test
    void firstFetchKeepsOnlyRecentItemsAndRecordsSuccess() {
        ExternalBlog blog = activeBlog();
        etag.set("\"v1\"");
        feed(item("g1", "Recent", link("/p/1"), NOW.minus(Duration.ofDays(10)), "<p>one</p>"),
                item("g2", "Old", link("/p/2"), NOW.minus(Duration.ofDays(40)), "two"));

        assertThat(collect(blog)).isEqualTo(FeedCollector.Outcome.OK);

        List<ExternalPost> saved = posts(blog);
        assertThat(saved).extracting(ExternalPost::getTitle).containsExactly("Recent");
        ExternalPost post = saved.getFirst();
        assertThat(post.getTopic().getId()).isEqualTo(topic.getId());
        assertThat(post.getTopicSource()).isEqualTo(TopicSource.DEFAULT);
        ExternalBlog after = reload(blog);
        assertThat(after.getLastFetchResult()).isEqualTo(FetchResultCode.OK);
        assertThat(after.getLastHttpStatus()).isEqualTo(200);
        assertThat(after.getEtag()).isEqualTo("\"v1\"");
        assertThat(after.getLastSuccessAt()).isEqualTo(NOW);
        assertThat(after.getLastFetchedAt()).isEqualTo(NOW);
        assertThat(after.getConsecutiveFailures()).isZero();
        assertThat(after.getNextFetchAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        verify(events).publishEvent(new PortalChangedEvent("external-fetch"));

        // 이후 수집은 기간 제한 없음
        etag.set("\"v2\"");
        feed(item("g2", "Old", link("/p/2"), NOW.minus(Duration.ofDays(40)), "two"));
        assertThat(collect(blog)).isEqualTo(FeedCollector.Outcome.OK);
        assertThat(posts(blog)).extracting(ExternalPost::getTitle).containsExactlyInAnyOrder("Recent", "Old");
    }

    @Test
    void firstFetchIsCappedAtMaxItems() {
        ExternalBlog blog = activeBlog();
        List<String[]> items = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            items.add(item("g" + i, "T" + i, link("/p/" + i), NOW.minusSeconds(i * 60L), "d"));
        }
        feed(items.toArray(String[][]::new));

        collect(blog);

        assertThat(posts(blog)).hasSize(100);
    }

    @Test
    void notModifiedWritesNoPosts() {
        ExternalBlog blog = activeBlog();
        etag.set("\"same\"");
        feed(item("g1", "One", link("/p/1"), NOW, "d"));
        collect(blog);
        clock.advance(Duration.ofMinutes(31));
        server.clearRequests();

        assertThat(collect(blog)).isEqualTo(FeedCollector.Outcome.NOT_MODIFIED);

        assertThat(server.requests().getFirst().header("If-None-Match")).isEqualTo("\"same\"");
        ExternalBlog after = reload(blog);
        assertThat(after.getLastFetchResult()).isEqualTo(FetchResultCode.NOT_MODIFIED);
        assertThat(after.getLastSuccessAt()).isEqualTo(clock.instant());
        assertThat(after.getLastFetchedAt()).isEqualTo(clock.instant());
        assertThat(after.getEtag()).isEqualTo("\"same\"");
        assertThat(after.getNextFetchAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(30)));
        assertThat(posts(blog)).hasSize(1);
    }

    @Test
    void updatesSamePostByGuidThenLinkKeepingTopic() {
        ExternalBlog blog = activeBlog();
        feed(item("g1", "Title", link("/p/1"), NOW, "body"), item(null, "No guid", link("/p/2"), NOW, "x"),
                item("g3", "Moves", link("/p/3"), NOW, "y"));
        collect(blog);
        List<ExternalPost> first = posts(blog);
        em.createQuery("update ExternalPost p set p.topic = :t, p.topicSource = :s where p.id = :id")
                .setParameter("t", other).setParameter("s", TopicSource.OWNER).setParameter("id", first.get(0).getId())
                .executeUpdate();

        feed(item("g1", "Title edited", link("/p/1-new"), NOW, "body"),
                item(null, "No guid edited", link("/p/2"), NOW, "x"),
                item("g3-changed", "Moves edited", link("/p/3"), NOW, "y"));
        collect(blog);

        List<ExternalPost> after = posts(blog);
        assertThat(after).hasSize(3);
        assertThat(after).extracting(ExternalPost::getId).containsExactlyElementsOf(
                first.stream().map(ExternalPost::getId).toList());
        assertThat(after.get(0).getTitle()).isEqualTo("Title edited");
        assertThat(after.get(0).getLink()).isEqualTo(link("/p/1-new"));
        assertThat(after.get(0).getTopic().getId()).isEqualTo(other.getId());
        assertThat(after.get(0).getTopicSource()).isEqualTo(TopicSource.OWNER);
        assertThat(after.get(1).getTitle()).isEqualTo("No guid edited");
        assertThat(after.get(2).getTitle()).isEqualTo("Moves edited");
        assertThat(after.get(2).getGuid()).isEqualTo("g3-changed");
    }

    @Test
    void removedPostIsNotRevived() {
        ExternalBlog blog = activeBlog();
        feed(item("g1", "Title", link("/p/1"), NOW, "body"));
        collect(blog);
        ExternalPost post = posts(blog).getFirst();
        post.remove(RemovedReason.ADMIN);
        em.merge(post);

        feed(item("g1", "Title again", link("/p/1"), NOW, "body"));
        collect(blog);

        List<ExternalPost> after = posts(blog);
        assertThat(after).hasSize(1);
        assertThat(after.getFirst().getTitle()).isEqualTo("Title");
        assertThat(after.getFirst().isActive()).isFalse();
    }

    @Test
    void failuresBackOffThenStopAfterSevenDaysAndNotify() {
        ExternalBlog blog = activeBlog();
        em.createQuery("update ExternalBlog b set b.feedUrl = :u where b.id = :id")
                .setParameter("u", server.uri("/broken.xml").toString()).setParameter("id", blog.getId())
                .executeUpdate();
        server.handle("/broken.xml", exchange -> {
            exchange.sendResponseHeaders(500, -1);
        });

        assertThat(collect(blog)).isEqualTo(FeedCollector.Outcome.FAILED);
        ExternalBlog once = reload(blog);
        assertThat(once.getConsecutiveFailures()).isEqualTo(1);
        assertThat(once.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(once.getLastFetchResult()).isEqualTo(FetchResultCode.HTTP_ERROR);
        assertThat(once.getLastHttpStatus()).isEqualTo(500);
        assertThat(once.getNextFetchAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));

        clock.advance(Duration.ofMinutes(30));
        collect(blog);
        assertThat(reload(blog).getNextFetchAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(60)));
        for (int i = 0; i < 8; i++) {
            clock.advance(Duration.ofMinutes(1));
            collect(blog);
        }
        assertThat(reload(blog).getNextFetchAt()).isEqualTo(clock.instant().plus(Duration.ofHours(12)));
        assertThat(em.createQuery("select count(n) from Notification n", Long.class).getSingleResult()).isZero();

        clock.set(NOW.plus(Duration.ofDays(7)));
        assertThat(collect(blog)).isEqualTo(FeedCollector.Outcome.STOPPED);

        ExternalBlog stopped = reload(blog);
        assertThat(stopped.getStatus()).isEqualTo(ExternalBlogStatus.STOPPED);
        assertThat(stopped.getNextFetchAt()).isNull();
        List<Notification> sent = em.createQuery("select n from Notification n", Notification.class)
                .getResultList();
        assertThat(sent).singleElement().satisfies(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.EXTERNAL_FEED_STOPPED);
            assertThat(n.getUser().getId()).isEqualTo(member.getId());
            assertThat(n.getParams()).containsEntry("lastResult", "HTTP_ERROR");
        });
        // 멈춘 블로그는 더 요청하지 않음
        server.clearRequests();
        assertThat(collect(blog)).isEqualTo(FeedCollector.Outcome.SKIPPED);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void adminDirectBlogStopsWithoutNotification() {
        ExternalBlog blog = x.blog(null, server.uri("/down.xml").toString(), topic, ExternalBlogStatus.ACTIVE);
        server.handle("/down.xml", exchange -> exchange.sendResponseHeaders(503, -1));
        collect(blog);
        clock.advance(Duration.ofDays(7));

        assertThat(collect(blog)).isEqualTo(FeedCollector.Outcome.STOPPED);
        assertThat(em.createQuery("select count(n) from Notification n", Long.class).getSingleResult()).isZero();
    }

    @Test
    void parseErrorAndInactiveBlogs() {
        ExternalBlog blog = activeBlog();
        feedBody.set("<rss><channel>");
        assertThat(collect(blog)).isEqualTo(FeedCollector.Outcome.FAILED);
        assertThat(reload(blog).getLastFetchResult()).isEqualTo(FetchResultCode.PARSE_ERROR);

        ExternalBlog paused = x.blog(member, "https://paused.example/feed", topic, ExternalBlogStatus.PAUSED);
        ExternalBlog pending = x.blog(member, "https://pending.example/feed", topic, ExternalBlogStatus.PENDING);
        server.clearRequests();
        assertThat(collect(paused)).isEqualTo(FeedCollector.Outcome.SKIPPED);
        assertThat(collect(pending)).isEqualTo(FeedCollector.Outcome.SKIPPED);
        assertThat(collector.collect(999_999L)).isEqualTo(FeedCollector.Outcome.SKIPPED);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void noPortalEventWithoutChanges() {
        ExternalBlog blog = activeBlog();
        feed(item("g1", "Same", link("/p/1"), NOW, "body"));
        collect(blog);
        verify(events).publishEvent(new PortalChangedEvent("external-fetch"));
        collect(blog);
        verify(events, org.mockito.Mockito.times(1)).publishEvent(new PortalChangedEvent("external-fetch"));
    }

    @Test
    void thumbnailsOnlyForVerifiedBlogs() {
        ExternalBlog blog = activeBlog();
        feed(item("g1", "Pic", link("/p/1"), NOW, "<![CDATA[<img src=\"" + link("/img.png") + "\">]]>"));
        collect(blog);
        assertThat(posts(blog).getFirst().getImageUrl()).isEqualTo(link("/img.png"));
        verify(thumbnails, never()).fetchFor(anyLong());

        ExternalBlog verifiedBlog = x.blog(member, server.uri("/verified.xml").toString(), topic,
                ExternalBlogStatus.ACTIVE);
        verifiedBlog.markVerified(NOW);
        server.respond("/verified.xml", 200, RSS, ExternalTestKit.rss("V", server.uri("/").toString(), "about",
                item("v1", "Pic", link("/v/1"), NOW, "<![CDATA[<img src=\"" + link("/img.png") + "\">]]>"),
                item("v2", "NoPic", link("/v/2"), NOW, "text")));
        collect(verifiedBlog);
        ExternalPost withImage = posts(verifiedBlog).stream().filter(p -> p.getTitle().equals("Pic")).findFirst()
                .orElseThrow();
        verify(thumbnails).fetchFor(withImage.getId());
        verify(thumbnails, org.mockito.Mockito.times(1)).fetchFor(anyLong());
    }

    @Test
    void oneFeedUsesAtMostFourQueriesPlusWritesPerNewPost() {
        ExternalBlog blog = activeBlog();
        feed(item("g1", "A", link("/p/1"), NOW, "a"), item("g2", "B", link("/p/2"), NOW, "b"),
                item("g3", "C", link("/p/3"), NOW, "c"));
        em.flush();
        em.clear();

        queryCounter.reset();
        collector.collect(blog.getId());
        em.flush();

        assertThat(queryCounter.count()).isLessThanOrEqualTo(4 + 3);
        assertThat(Map.of("posts", posts(blog).size())).containsEntry("posts", 3);

        // 바뀐 것이 없는 다음 수집: 읽기 1 + 성공 기록 1 + IN 2
        em.clear();
        queryCounter.reset();
        collector.collect(blog.getId());
        em.flush();
        assertThat(queryCounter.count()).isLessThanOrEqualTo(4);
    }
}
