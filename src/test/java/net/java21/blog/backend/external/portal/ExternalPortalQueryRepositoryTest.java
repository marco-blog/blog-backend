package net.java21.blog.backend.external.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostDailyClick;
import net.java21.blog.backend.external.domain.RemovedReason;
import net.java21.blog.backend.portal.domain.PortalExclusion;
import net.java21.blog.backend.portal.service.ExternalPortalSource.PopularityCandidate;
import net.java21.blog.backend.portal.service.PortalCursor;
import net.java21.blog.backend.portal.service.PortalItem;
import net.java21.blog.backend.portal.service.PortalKey;
import net.java21.blog.backend.portal.service.PortalSourceType;
import net.java21.blog.backend.support.ExternalFixtures;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * 007 T042: 외부 글 포털 노출 조건 행렬(글 ACTIVE/REMOVED × 블로그 상태 × 포털 제외 × 미래 발행 × 주제 숨김), 최신(커서·LIMIT), 주제
 * 페이지 가벼운 행과 수, 카드(인증 블로그만 썸네일) 쿼리 1회, 인기 후보, 주제별 최근 수 쿼리 1회 (FR-123, research E13).
 */
@JpaRepositoryTest
@Import(ExternalPortalQueryRepository.class)
class ExternalPortalQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private ExternalPortalQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private ExternalFixtures x;
    private User member;
    private User admin;
    private Topic major;
    private Topic minor;
    private Topic other;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        x = new ExternalFixtures(em);
        member = fx.user("member");
        admin = fx.user("admin");
        major = fx.topic(null, "knowledge", 0);
        minor = fx.topic(major, "it-internet", 0);
        other = fx.topic(major, "science", 1);
    }

    private ExternalPost post(ExternalBlogStatus status, String title, Instant publishedAt) {
        ExternalBlog blog = x.blog(member, minor, status);
        return x.post(blog, title, minor, publishedAt);
    }

    private List<String> visibleTitles() {
        return repository.findLatest(NOW, null, 100).stream().map(item -> item.card().title()).toList();
    }

    @Test
    void exposureMatrix() {
        post(ExternalBlogStatus.ACTIVE, "active", NOW.minusSeconds(10));
        post(ExternalBlogStatus.PAUSED, "paused", NOW.minusSeconds(20));
        post(ExternalBlogStatus.STOPPED, "stopped", NOW.minusSeconds(30));
        post(ExternalBlogStatus.RELEASED, "released-kept", NOW.minusSeconds(40));
        post(ExternalBlogStatus.PENDING, "pending", NOW.minusSeconds(50));
        post(ExternalBlogStatus.REJECTED, "rejected", NOW.minusSeconds(60));
        post(ExternalBlogStatus.BLOCKED, "blocked", NOW.minusSeconds(70));
        ExternalBlog active = x.blog(member, minor, ExternalBlogStatus.ACTIVE);
        x.removed(active, "removed", minor, RemovedReason.LINK_BROKEN);
        x.post(active, "future", minor, NOW.plusSeconds(60));
        ExternalPost excluded = x.post(active, "excluded", minor, NOW.minusSeconds(80));
        em.persist(new PortalExclusion(excluded, "spam", admin));
        Topic hiddenMinor = fx.topic(major, "hidden-minor", 2);
        hiddenMinor.hide();
        x.post(active, "hidden-minor", hiddenMinor, NOW.minusSeconds(90));
        Topic hiddenMajor = fx.topic(null, "hidden-major", 1);
        Topic underHidden = fx.topic(hiddenMajor, "under-hidden", 0);
        hiddenMajor.hide();
        x.post(active, "hidden-major", underHidden, NOW.minusSeconds(100));
        fx.flushAndClear();

        assertThat(visibleTitles()).containsExactly("active", "paused", "stopped", "released-kept");
    }

    @Test
    void latestOrdersByTimeThenIdAndFollowsCursor() {
        ExternalBlog blog = x.blog(member, minor, ExternalBlogStatus.ACTIVE);
        Instant t = NOW.minusSeconds(100);
        ExternalPost a = x.post(blog, "a", minor, t);
        ExternalPost b = x.post(blog, "b", minor, t);
        ExternalPost c = x.post(blog, "c", minor, NOW.minusSeconds(200));
        ExternalPost d = x.post(blog, "d", minor, NOW.minusSeconds(50));
        fx.flushAndClear();

        queryCounter.reset();
        List<PortalItem> first = repository.findLatest(NOW, null, 2);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(first).extracting(PortalItem::id).containsExactly(d.getId(), b.getId());
        assertThat(first.get(0).source()).isEqualTo(PortalSourceType.EXTERNAL);

        List<PortalItem> next = repository.findLatest(NOW, first.get(1).position(), 10);
        assertThat(next).extracting(PortalItem::id).containsExactly(a.getId(), c.getId());

        // 내부 글 위치(같은 시각): 같은 시각 외부 글은 모두 그 뒤
        List<PortalItem> afterInternal = repository.findLatest(NOW,
                new PortalCursor.Position(t, 1L, PortalSourceType.INTERNAL), 10);
        assertThat(afterInternal).extracting(PortalItem::id).containsExactly(b.getId(), a.getId(), c.getId());
    }

    @Test
    void cardCarriesExternalBlogAndThumbnailOnlyWhenVerified() {
        ExternalBlog verified = x.blog(member, "https://verified.example/feed", minor, ExternalBlogStatus.ACTIVE);
        verified.markVerified(NOW);
        ExternalPost withThumb = x.post(verified, "verified", minor, NOW.minusSeconds(10));
        withThumb.attachThumbnail("AbCdEfGhIjKlMnOpQrStUv");
        ExternalBlog plain = x.blog(member, "https://plain.example/feed", minor, ExternalBlogStatus.ACTIVE);
        ExternalPost noThumb = x.post(plain, "plain", minor, NOW.minusSeconds(20));
        noThumb.attachThumbnail("BbCdEfGhIjKlMnOpQrStUv");
        fx.flushAndClear();

        queryCounter.reset();
        List<PortalItem> cards = repository.findCards(List.of(noThumb.getId(), withThumb.getId(), 999_999L), NOW);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(cards).extracting(PortalItem::id).containsExactly(noThumb.getId(), withThumb.getId());
        var card = cards.get(1).card();
        assertThat(card.source()).isEqualTo("EXTERNAL");
        assertThat(card.thumbnailUrl()).isEqualTo("/media/external/AbCdEfGhIjKlMnOpQrStUv");
        assertThat(card.visitUrl()).isEqualTo("/api/v1/external-posts/" + withThumb.getId() + "/visit");
        assertThat(card.blog().handle()).isNull();
        assertThat(card.blog().title()).isEqualTo("Blog https://verified.example/feed");
        assertThat(card.author()).isNull();
        assertThat(card.externalBlog().id()).isEqualTo(verified.getId());
        assertThat(card.externalBlog().siteHost()).isEqualTo("verified.example");
        assertThat(card.topicId()).isEqualTo(minor.getId());
        assertThat(card.likeCount()).isZero();
        assertThat(cards.get(0).card().thumbnailUrl()).isNull();
        assertThat(cards.get(1).capKey()).isEqualTo("E:" + verified.getId());
        assertThat(repository.findCards(List.of(), NOW)).isEmpty();
    }

    @Test
    void topicKeysAndCount() {
        ExternalBlog blog = x.blog(member, minor, ExternalBlogStatus.ACTIVE);
        ExternalPost p1 = x.post(blog, "1", minor, NOW.minusSeconds(10));
        ExternalPost p2 = x.post(blog, "2", minor, NOW.minusSeconds(20));
        x.post(blog, "3", other, NOW.minusSeconds(30));
        x.removed(blog, "gone", minor, RemovedReason.ADMIN);
        fx.flushAndClear();

        queryCounter.reset();
        List<PortalKey> keys = repository.findTopicKeys(NOW, List.of(minor.getId()), 10);
        long count = repository.countTopic(NOW, List.of(minor.getId()));
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(keys).extracting(PortalKey::id).containsExactly(p1.getId(), p2.getId());
        assertThat(count).isEqualTo(2);
        assertThat(repository.findTopicKeys(NOW, List.of(minor.getId(), other.getId()), 1)).hasSize(1);
        assertThat(repository.findTopicKeys(NOW, List.of(), 10)).isEmpty();
        assertThat(repository.countTopic(NOW, List.of())).isZero();
    }

    @Test
    void popularityCandidatesSumRecentClicksOfVisiblePosts() {
        ExternalBlog blog = x.blog(member, minor, ExternalBlogStatus.ACTIVE);
        ExternalPost hot = x.post(blog, "hot", minor, NOW.minus(Duration.ofDays(1)));
        ExternalPost gone = x.post(blog, "gone", minor, NOW.minus(Duration.ofDays(1)));
        LocalDate today = LocalDate.ofInstant(NOW, ZoneOffset.UTC);
        em.persist(new ExternalPostDailyClick(hot, today, 3));
        em.persist(new ExternalPostDailyClick(hot, today.minusDays(2), 2));
        em.persist(new ExternalPostDailyClick(hot, today.minusDays(10), 50));
        em.persist(new ExternalPostDailyClick(gone, today, 9));
        gone.remove(RemovedReason.LINK_BROKEN);
        fx.flushAndClear();

        queryCounter.reset();
        List<PopularityCandidate> rows = repository.findPopularityCandidates(NOW, today.minusDays(6));
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(hot.getId());
            assertThat(row.blogId()).isEqualTo(blog.getId());
            assertThat(row.topicId()).isEqualTo(minor.getId());
            assertThat(row.clicks()).isEqualTo(5);
        });
    }

    @Test
    void recentCountsByTopicAndVisibleLink() {
        ExternalBlog kept = x.blog(member, minor, ExternalBlogStatus.RELEASED);
        ExternalPost keptPost = x.post(kept, "kept", minor, NOW.minus(Duration.ofDays(3)));
        ExternalBlog blog = x.blog(member, minor, ExternalBlogStatus.ACTIVE);
        x.post(blog, "recent", other, NOW.minus(Duration.ofDays(1)));
        x.post(blog, "old", minor, NOW.minus(Duration.ofDays(40)));
        x.removed(blog, "removed", minor, RemovedReason.ADMIN);
        ExternalBlog blocked = x.blog(member, minor, ExternalBlogStatus.BLOCKED);
        ExternalPost blockedPost = x.post(blocked, "blocked", minor, NOW.minus(Duration.ofDays(1)));
        fx.flushAndClear();

        queryCounter.reset();
        Map<Long, Long> counts = repository.countRecentByTopic(NOW, NOW.minus(Duration.ofDays(30)));
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(minor.getId(), 1L, other.getId(), 1L));

        assertThat(repository.findVisibleLink(keptPost.getId(), NOW)).contains(keptPost.getLink());
        assertThat(repository.findVisibleLink(blockedPost.getId(), NOW)).isEmpty();
        assertThat(repository.findVisibleLink(999_999L, NOW)).isEmpty();
    }

    @Test
    void hostFallsBackToFeedUrl() {
        assertThat(ExternalPortalQueryRepository.host(null, "https://feed.example/rss")).isEqualTo("feed.example");
        assertThat(ExternalPortalQueryRepository.host("https://site.example/", "https://feed.example/rss"))
                .isEqualTo("site.example");
        assertThat(ExternalPortalQueryRepository.host("::bad::", null)).isEmpty();
    }
}
