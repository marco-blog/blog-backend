package net.java21.blog.backend.seo.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.topic.domain.Topic;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * 사이트맵 조회(T063, FR-037, research D5): 본문 노출 가능 글만 id 순 n번째 묶음, 전체 수·가장 늦은 수정 시각, 본문 노출 가능 글이 있는
 * 블로그와 그 최근 발행 시각. 각 조회는 쿼리 1회이며 글·블로그 수와 무관하다.
 */
@JpaRepositoryTest
@Import(SitemapQueryRepository.class)
class SitemapQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private SitemapQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private Post a1;
    private Post a2;
    private Post c1;
    private Blog a;
    private Blog c;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        a = fx.blog(fx.user("marco"), "marco");
        c = fx.blog(fx.user("third"), "third");
        Blog empty = fx.blog(fx.user("empty"), "empty");
        a1 = fx.published(a, "A1", null, 1);
        c1 = fx.published(c, "C1", null, 2);
        a2 = fx.published(a, "A2", null, 3);
        fx.post(a, "비공개", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 4);
        fx.post(a, "임시저장", null, PostStatus.DRAFT, PostVisibility.PUBLIC, 5);
        fx.post(a, "휴지통", null, PostStatus.DELETED, PostVisibility.PUBLIC, 6);
        fx.post(empty, "비공개만", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 7);
        User suspended = fx.user("suspended");
        User withdrawn = fx.user("withdrawn");
        fx.published(fx.blog(suspended, "s"), "정지", null, 8);
        fx.published(fx.blog(withdrawn, "w"), "탈퇴", null, 9);
        Blog deleted = fx.blog(fx.user("del"), "d");
        fx.published(deleted, "삭제된 블로그", null, 10);
        TestEntities.with(suspended, "status", UserStatus.SUSPENDED);
        TestEntities.with(withdrawn, "status", UserStatus.WITHDRAWN);
        deleted.delete(NOW);
        fx.flushAndClear();
    }

    @Test
    void postsAreBodyVisibleOnlyInIdOrderAndChunked() {
        queryCounter.reset();
        List<SitemapPostRow> first = repository.findPosts(0, 2);
        List<SitemapPostRow> second = repository.findPosts(1, 2);
        List<SitemapPostRow> beyond = repository.findPosts(2, 2);

        assertThat(queryCounter.count()).isEqualTo(3);
        assertThat(first).extracting(SitemapPostRow::id).containsExactly(a1.getId(), c1.getId());
        assertThat(second).extracting(SitemapPostRow::id).containsExactly(a2.getId());
        assertThat(beyond).isEmpty();
        assertThat(first.getFirst().blogHandle()).isEqualTo("marco");
        assertThat(first.get(1).blogHandle()).isEqualTo("third");
        assertThat(first.getFirst().updatedAt()).isNotNull();
    }

    @Test
    void statsCountBodyVisiblePostsInOneQuery() {
        queryCounter.reset();
        SitemapStats stats = repository.stats();

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(stats.postCount()).isEqualTo(3);
        assertThat(stats.lastModified()).isNotNull();
    }

    @Test
    void blogsWithBodyVisiblePostsAndTheirLatestPublication() {
        queryCounter.reset();
        List<SitemapBlogRow> blogs = repository.findBlogs();

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(blogs).extracting(SitemapBlogRow::handle).containsExactly("marco", "third");
        assertThat(blogs.getFirst().lastPublishedAt()).isEqualTo(JpaFixtures.T0.plusSeconds(180));
        assertThat(blogs.get(1).lastPublishedAt()).isEqualTo(JpaFixtures.T0.plusSeconds(120));
    }

    @Test
    void emptyServiceHasNoPostsOrBlogs() {
        em.createQuery("delete from Post").executeUpdate();

        assertThat(repository.stats().postCount()).isZero();
        assertThat(repository.stats().lastModified()).isNull();
        assertThat(repository.findBlogs()).isEmpty();
        assertThat(repository.findPosts(0, 10)).isEmpty();
    }

    /** 003 T061: 운영자 숨김이 아닌 주제 경로(대분류·부모 slug가 붙은 소분류), 숨긴 대분류의 소분류 제외, 쿼리 1회. */
    @Test
    void topicPathsSkipAdminHiddenTopicsAndChildrenOfHiddenMajors() {
        Topic life = fx.topic(null, "life", 1);
        Topic knowledge = fx.topic(null, "knowledge", 0);
        fx.topic(life, "daily", 1);
        fx.topic(life, "pets", 0);
        fx.topic(knowledge, "it-internet", 0).hide();
        Topic culture = fx.topic(null, "culture", 2);
        fx.topic(culture, "movie", 0);
        culture.hide();
        fx.flushAndClear();

        queryCounter.reset();
        List<String> paths = repository.findTopicPaths();

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(paths).containsExactly("/topics/knowledge", "/topics/life", "/topics/life/pets",
                "/topics/life/daily");
    }
}
