package net.java21.blog.backend.portal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.portal.domain.PortalCuration;
import net.java21.blog.backend.portal.service.PortalCriteria;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.tag.domain.Tag;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * 포털 메인 영역 쿼리(003 T033, FR-087·091·092): 인기 태그, 새로 시작한 블로그, 지금 기간 안 추천. 각각 쿼리 1회.
 */
@JpaRepositoryTest
@Import(PortalSectionQueryRepository.class)
class PortalSectionQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final PortalCriteria CRITERIA = new PortalCriteria(NOW, Duration.ofHours(24), 200);
    private static final String TEXT = "가".repeat(200);
    private static final Instant WEEK_AGO = NOW.minus(Duration.ofDays(7));
    private static final Instant MONTH_AGO = NOW.minus(Duration.ofDays(30));

    @Autowired
    private EntityManager em;
    @Autowired
    private PortalSectionQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        owner = fx.user("marco");
        blog = fx.blog(owner, "marco");
    }

    @Test
    void popularTagsCountRecentPortalPostsOnlyOrderedByCountThenName() {
        Tag spring = fx.tag("spring");
        Tag java = fx.tag("java");
        Tag alpha = fx.tag("alpha");
        Tag old = fx.tag("old");
        Tag hidden = fx.tag("hidden");
        Post p1 = fx.publishedText(blog, "1", TEXT, null, NOW.minusSeconds(60));
        Post p2 = fx.publishedText(blog, "2", TEXT, null, NOW.minusSeconds(120));
        Post p3 = fx.publishedText(blog, "3", TEXT, null, WEEK_AGO);
        Post oldPost = fx.publishedText(blog, "old", TEXT, null, WEEK_AGO.minusSeconds(1));
        Post privatePost = fx.publishedText(blog, "private", TEXT, null, PostStatus.PUBLISHED, PostVisibility.PRIVATE,
                NOW.minusSeconds(60));
        Post shortPost = fx.publishedText(blog, "short", "짧다", null, NOW.minusSeconds(60));
        fx.tagPost(p1, spring, java, alpha);
        fx.tagPost(p2, spring, java);
        fx.tagPost(p3, spring);
        fx.tagPost(oldPost, old, spring);
        fx.tagPost(privatePost, hidden);
        fx.tagPost(shortPost, hidden);
        fx.joinedAt(owner, MONTH_AGO);
        fx.flushAndClear();

        queryCounter.reset();
        List<PopularTagRow> tags = repository.findPopularTags(CRITERIA, WEEK_AGO, 20);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(tags).containsExactly(new PopularTagRow("spring", 3), new PopularTagRow("java", 2),
                new PopularTagRow("alpha", 1));
        assertThat(repository.findPopularTags(CRITERIA, WEEK_AGO, 2)).extracting(PopularTagRow::name)
                .containsExactly("spring", "java");
    }

    @Test
    void newBlogsAreRecentFirstPublishersWithAPortalPostNewestFirst() {
        Media cover = new Media(owner, "cover00000000000000000", MediaPurpose.BLOG_COVER, "c.png", "2026/10/c.png",
                "image/png", 10, 10, 10);
        em.persist(cover);
        blog.changeCoverMedia(cover);
        blog.changeDescription("소개");
        blog.markFirstPublished(NOW.minus(Duration.ofDays(3)));
        fx.publishedText(blog, "a", TEXT, null, NOW.minusSeconds(60));
        fx.publishedText(blog, "b", TEXT, null, NOW.minusSeconds(120));

        Blog newer = newBlog("newer", NOW.minus(Duration.ofDays(1)), TEXT);
        Blog boundary = newBlog("boundary", MONTH_AGO, TEXT);
        newBlog("tooold", MONTH_AGO.minusSeconds(1), TEXT);
        newBlog("noportalpost", NOW.minus(Duration.ofDays(2)), "짧다");
        Blog off = newBlog("off", NOW.minus(Duration.ofDays(2)), TEXT);
        off.changePortalSettings(false, null);
        Blog deleted = newBlog("deleted", NOW.minus(Duration.ofDays(2)), TEXT);
        deleted.delete(NOW);
        fx.joinedAt(owner, MONTH_AGO);
        fx.flushAndClear();

        queryCounter.reset();
        List<NewBlogRow> blogs = repository.findNewBlogs(CRITERIA, MONTH_AGO, 6);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(blogs).extracting(NewBlogRow::handle).containsExactly("newer", "marco", "boundary");
        assertThat(blogs.get(1)).isEqualTo(new NewBlogRow("marco", "marco 블로그", "소개", "cover00000000000000000",
                "marco", null, NOW.minus(Duration.ofDays(3))));
        assertThat(repository.findNewBlogs(CRITERIA, MONTH_AGO, 1)).extracting(NewBlogRow::handle)
                .containsExactly(newer.getHandle());
        assertThat(boundary.getHandle()).isEqualTo("boundary");
    }

    @Test
    void activeCurationsIncludeStartExcludeEndAndDropIneligiblePostsInSortOrder() {
        Post a = fx.publishedText(blog, "a", TEXT, null, NOW.minusSeconds(60));
        Post b = fx.publishedText(blog, "b", TEXT, null, NOW.minusSeconds(60));
        Post c = fx.publishedText(blog, "c", TEXT, null, NOW.minusSeconds(60));
        Post privatePost = fx.publishedText(blog, "private", TEXT, null, PostStatus.PUBLISHED, PostVisibility.PRIVATE,
                NOW.minusSeconds(60));
        Post ended = fx.publishedText(blog, "ended", TEXT, null, NOW.minusSeconds(60));
        Post future = fx.publishedText(blog, "future", TEXT, null, NOW.minusSeconds(60));
        em.persist(new PortalCuration(a, NOW.minusSeconds(10), NOW.plusSeconds(10), 2, owner));
        em.persist(new PortalCuration(b, NOW, NOW.plusSeconds(10), 1, owner));
        em.persist(new PortalCuration(c, NOW.minusSeconds(10), NOW.plusSeconds(10), 2, owner));
        em.persist(new PortalCuration(privatePost, NOW.minusSeconds(10), NOW.plusSeconds(10), 0, owner));
        em.persist(new PortalCuration(ended, NOW.minusSeconds(10), NOW, 0, owner));
        em.persist(new PortalCuration(future, NOW.plusSeconds(1), NOW.plusSeconds(10), 0, owner));
        em.persist(new PortalCuration(a, NOW.minusSeconds(5), NOW.plusSeconds(10), 3, owner));
        fx.joinedAt(owner, MONTH_AGO);
        fx.flushAndClear();

        queryCounter.reset();
        List<Long> ids = repository.findActiveCurationPostIds(CRITERIA, NOW, 5);
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(ids).containsExactly(b.getId(), a.getId(), c.getId());
    }

    private Blog newBlog(String handle, Instant firstPublishedAt, String text) {
        User u = fx.user(handle);
        Blog b = fx.blog(u, handle);
        b.markFirstPublished(firstPublishedAt);
        fx.publishedText(b, handle, text, null, NOW.minusSeconds(60));
        fx.joinedAt(u, MONTH_AGO);
        return b;
    }
}
