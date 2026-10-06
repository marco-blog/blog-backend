package net.java21.blog.backend.portal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostDailyStatsRepository;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/** 인기 점수 신호(003 T034, FR-086, research P4): 네 집계와 후보 글, 각 쿼리 1회. */
@JpaRepositoryTest
@Import(PopularitySignalQueryRepository.class)
class PopularitySignalQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-06");
    private static final Instant SINCE = NOW.minus(Duration.ofDays(7));
    private static final PortalCriteria CRITERIA = new PortalCriteria(NOW, Duration.ofHours(24), 200);
    private static final String TEXT = "가".repeat(200);

    @Autowired
    private EntityManager em;
    @Autowired
    private PopularitySignalQueryRepository repository;
    @Autowired
    private PostDailyStatsRepository dailyStats;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private User owner;
    private Blog blog;
    private Post post;
    private Post other;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        owner = fx.user("marco");
        blog = fx.blog(owner, "marco");
        post = fx.publishedText(blog, "a", TEXT, null, NOW.minus(Duration.ofDays(1)));
        other = fx.publishedText(blog, "b", TEXT, null, NOW.minus(Duration.ofDays(1)));
        em.flush();
    }

    @Test
    void dailyStatsAreSummedFromTheGivenDate() {
        dailyStats.upsertView(post.getId(), TODAY, NOW);
        dailyStats.upsertView(post.getId(), TODAY, NOW);
        dailyStats.upsertReadComplete(post.getId(), TODAY, NOW);
        dailyStats.upsertView(post.getId(), TODAY.minusDays(6), NOW);
        dailyStats.upsertView(post.getId(), TODAY.minusDays(7), NOW);
        dailyStats.upsertReadComplete(other.getId(), TODAY.minusDays(7), NOW);

        queryCounter.reset();
        Map<Long, long[]> sums = repository.sumDailyStats(TODAY.minusDays(6));
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(sums).containsOnlyKeys(post.getId());
        assertThat(sums.get(post.getId())).containsExactly(3L, 1L);
    }

    @Test
    void likesAreCountedSinceTheGivenInstant() {
        User r1 = fx.user("r1");
        User r2 = fx.user("r2");
        User r3 = fx.user("r3");
        em.flush();
        like(r1, post, NOW.minusSeconds(60));
        like(r2, post, SINCE);
        like(r3, post, SINCE.minusSeconds(1));
        like(r1, other, NOW.minusSeconds(60));

        queryCounter.reset();
        Map<Long, Long> likes = repository.countLikes(SINCE);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(likes).containsExactlyInAnyOrderEntriesOf(Map.of(post.getId(), 2L, other.getId(), 1L));
    }

    @Test
    void commentsAreCountedSinceTheGivenInstantExcludingDeleted() {
        User reader = fx.user("reader");
        Comment recent = new Comment(post, reader, null, "1");
        Comment deleted = new Comment(post, reader, null, "2");
        deleted.markDeleted();
        Comment old = new Comment(post, reader, null, "3");
        Comment reply = new Comment(other, reader, null, "4");
        for (Comment c : List.of(recent, deleted, old, reply)) {
            em.persist(c);
        }
        em.flush();
        createdAt("comments", recent.getId(), NOW.minusSeconds(60));
        createdAt("comments", deleted.getId(), NOW.minusSeconds(60));
        createdAt("comments", old.getId(), SINCE.minusSeconds(1));
        createdAt("comments", reply.getId(), SINCE);

        queryCounter.reset();
        Map<Long, Long> comments = repository.countComments(SINCE);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(comments).containsExactlyInAnyOrderEntriesOf(Map.of(post.getId(), 1L, other.getId(), 1L));
    }

    @Test
    void candidatesArePortalPostsWithBlogTopicAndPublishedAt() {
        Topic minor = fx.topic(fx.topic(null, "life", 0), "daily", 0);
        Post topical = fx.publishedText(blog, "t", TEXT, minor, NOW.minusSeconds(3600));
        Post privatePost = fx.publishedText(blog, "p", TEXT, null, PostStatus.PUBLISHED, PostVisibility.PRIVATE,
                NOW.minusSeconds(60));
        fx.joinedAt(owner, NOW.minus(Duration.ofDays(30)));
        fx.flushAndClear();

        queryCounter.reset();
        List<PopularityCandidateRow> rows = repository.findCandidates(CRITERIA,
                Set.of(topical.getId(), privatePost.getId(), post.getId(), 999_999L));
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(rows).containsExactlyInAnyOrder(
                new PopularityCandidateRow(topical.getId(), blog.getId(), minor.getId(), NOW.minusSeconds(3600)),
                new PopularityCandidateRow(post.getId(), blog.getId(), null, NOW.minus(Duration.ofDays(1))));
        assertThat(repository.findCandidates(CRITERIA, Set.of())).isEmpty();
    }

    private void like(User user, Post target, Instant at) {
        em.createNativeQuery("INSERT INTO post_likes (user_id, post_id, created_at) VALUES (:u, :p, :t)")
                .setParameter("u", user.getId()).setParameter("p", target.getId()).setParameter("t", at)
                .executeUpdate();
    }

    private void createdAt(String table, Long id, Instant at) {
        em.createNativeQuery("UPDATE " + table + " SET created_at = :t WHERE id = :id").setParameter("t", at)
                .setParameter("id", id).executeUpdate();
    }
}
