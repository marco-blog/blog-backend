package net.java21.blog.backend.portal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.portal.service.PortalCriteria;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 주제 페이지 최신순(003 T058, FR-078 AS1): 주어진 소분류의 포털 노출 글만, 최신순, 페이지·전체 수, 쿼리 2회(목록 + 수).
 */
@JpaRepositoryTest
@Import(TopicPostQueryRepository.class)
class TopicPostQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final PortalCriteria CRITERIA = new PortalCriteria(NOW, Duration.ofHours(24), 200);
    private static final String TEXT = "가".repeat(200);

    @Autowired
    private EntityManager em;
    @Autowired
    private TopicPostQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private Blog blog;
    private Topic daily;
    private Topic pets;
    private Topic other;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        User owner = fx.user("marco");
        blog = fx.blog(owner, "marco");
        Topic life = fx.topic(null, "life", 0);
        daily = fx.topic(life, "daily", 0);
        pets = fx.topic(life, "pets", 1);
        other = fx.topic(fx.topic(null, "sports", 1), "soccer", 0);
        fx.joinedAt(owner, NOW.minus(Duration.ofDays(30)));
    }

    @Test
    void minorTopicListsOnlyItsPortalPostsNewestFirstWithTotal() {
        Post d1 = fx.publishedText(blog, "d1", TEXT, daily, NOW.minusSeconds(300));
        Post d2 = fx.publishedText(blog, "d2", TEXT, daily, NOW.minusSeconds(200));
        Post d3 = fx.publishedText(blog, "d3", TEXT, daily, NOW.minusSeconds(100));
        fx.publishedText(blog, "private", TEXT, daily, PostStatus.PUBLISHED, PostVisibility.PRIVATE,
                NOW.minusSeconds(50));
        fx.publishedText(blog, "short", "짧다", daily, NOW.minusSeconds(50));
        fx.publishedText(blog, "pets", TEXT, pets, NOW.minusSeconds(50));
        fx.publishedText(blog, "none", TEXT, null, NOW.minusSeconds(50));
        fx.flushAndClear();

        queryCounter.reset();
        Page<PortalCardRow> first = repository.findLatest(CRITERIA, List.of(daily.getId()), PageRequest.of(0, 2));
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(first.getContent()).extracting(PortalCardRow::id).containsExactly(d3.getId(), d2.getId());
        assertThat(first.getTotalElements()).isEqualTo(3);

        Page<PortalCardRow> second = repository.findLatest(CRITERIA, List.of(daily.getId()), PageRequest.of(1, 2));
        assertThat(second.getContent()).extracting(PortalCardRow::id).containsExactly(d1.getId());
        assertThat(second.getTotalElements()).isEqualTo(3);
    }

    @Test
    void majorTopicCombinesItsMinorsAndEmptyTopicListIsEmpty() {
        Post d = fx.publishedText(blog, "d", TEXT, daily, NOW.minusSeconds(300));
        Post p = fx.publishedText(blog, "p", TEXT, pets, NOW.minusSeconds(200));
        fx.publishedText(blog, "s", TEXT, other, NOW.minusSeconds(100));
        fx.flushAndClear();

        Page<PortalCardRow> page = repository.findLatest(CRITERIA, List.of(daily.getId(), pets.getId()),
                PageRequest.of(0, 20));
        assertThat(page.getContent()).extracting(PortalCardRow::id).containsExactly(p.getId(), d.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);

        queryCounter.reset();
        assertThat(repository.findLatest(CRITERIA, List.of(), PageRequest.of(0, 20))).isEmpty();
        assertThat(queryCounter.count()).isZero();
    }
}
