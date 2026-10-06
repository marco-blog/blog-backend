package net.java21.blog.backend.portal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.portal.service.PortalCursor;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
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
 * 포털 카드 쿼리(003 T032, FR-085, research P6·P7): 카드 projection, id 목록 읽기(순서 유지·노출 아닌 글 빠짐), 최신 글 커서,
 * 쿼리 수가 카드 수와 무관(1회).
 */
@JpaRepositoryTest
@Import(PortalCardQueryRepository.class)
class PortalCardQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final PortalCriteria CRITERIA = new PortalCriteria(NOW, Duration.ofHours(24), 200);
    private static final String TEXT = "가".repeat(200);

    @Autowired
    private EntityManager em;
    @Autowired
    private PortalCardQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private User owner;
    private Blog blog;
    private Topic minor;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        owner = fx.user("marco");
        blog = fx.blog(owner, "marco");
        minor = fx.topic(fx.topic(null, "knowledge", 0), "it-internet", 0);
    }

    @Test
    void cardProjectionCarriesPostBlogAuthorAndCounts() {
        Media profile = new Media(owner, "marcoprofile0000000000", MediaPurpose.PROFILE, "p.png", "2026/10/p.png",
                "image/png", 10, 10, 10);
        em.persist(profile);
        owner.changeProfileMedia(profile);
        Post p = new Post(blog, "제목");
        p.assignTopic(minor);
        p.publish("제목", TEXT, "<p>x</p>", TEXT, "요약", "/media/thumb000000000000000", PostVisibility.PUBLIC, true,
                NOW.minusSeconds(60));
        em.persist(p);
        fx.joinedAt(owner, NOW.minus(Duration.ofDays(2)));
        em.createNativeQuery("UPDATE posts SET like_count = 3, comment_count = 2 WHERE id = :id")
                .setParameter("id", p.getId()).executeUpdate();
        fx.flushAndClear();

        queryCounter.reset();
        List<PortalCardRow> rows = repository.findByIds(List.of(p.getId()), CRITERIA);
        assertThat(queryCounter.count()).isEqualTo(1);

        assertThat(rows).containsExactly(new PortalCardRow(p.getId(), "제목", "요약", "/media/thumb000000000000000",
                minor.getId(), blog.getId(), "marco", "marco 블로그", "marco", "marcoprofile0000000000",
                NOW.minusSeconds(60), 3, 2));
    }

    @Test
    void findByIdsKeepsTheGivenOrderAndDropsPostsNotOnThePortal() {
        Post a = fx.publishedText(blog, "a", TEXT, null, NOW.minusSeconds(300));
        Post b = fx.publishedText(blog, "b", TEXT, null, NOW.minusSeconds(200));
        Post hidden = fx.publishedText(blog, "private", TEXT, null, PostStatus.PUBLISHED, PostVisibility.PRIVATE,
                NOW.minusSeconds(100));
        Post c = fx.publishedText(blog, "c", TEXT, null, NOW.minusSeconds(100));
        fx.joinedAt(owner, NOW.minus(Duration.ofDays(2)));
        fx.flushAndClear();

        queryCounter.reset();
        List<PortalCardRow> rows = repository.findByIds(List.of(c.getId(), hidden.getId(), a.getId(), b.getId(),
                a.getId()), CRITERIA);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(rows).extracting(PortalCardRow::id).containsExactly(c.getId(), a.getId(), b.getId());
        assertThat(repository.findByIds(List.of(), CRITERIA)).isEmpty();
    }

    @Test
    void findLatestOrdersByPublishedAtThenIdAndContinuesAfterTheCursor() {
        Instant same = NOW.minusSeconds(100);
        Post p1 = fx.publishedText(blog, "1", TEXT, null, NOW.minusSeconds(10));
        Post p2 = fx.publishedText(blog, "2", TEXT, null, same);
        Post p3 = fx.publishedText(blog, "3", TEXT, null, same);
        Post p4 = fx.publishedText(blog, "4", TEXT, null, same);
        Post p5 = fx.publishedText(blog, "5", TEXT, null, NOW.minusSeconds(500));
        fx.publishedText(blog, "short", "가".repeat(199), null, NOW.minusSeconds(5));
        fx.joinedAt(owner, NOW.minus(Duration.ofDays(2)));
        fx.flushAndClear();

        queryCounter.reset();
        List<PortalCardRow> first = repository.findLatest(CRITERIA, null, 3);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(first).extracting(PortalCardRow::id).containsExactly(p1.getId(), p4.getId(), p3.getId());

        PortalCardRow last = first.get(2);
        List<PortalCardRow> next = repository.findLatest(CRITERIA,
                new PortalCursor.Position(last.publishedAt(), last.id()), 3);
        assertThat(next).extracting(PortalCardRow::id).containsExactly(p2.getId(), p5.getId());
    }
}
