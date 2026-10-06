package net.java21.blog.backend.syndication.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * 블로그 피드 글 선택(T082, FR-045·047, AS3·5): 블로그의 "목록 노출 가능" 글만 발행 최신순 {@code limit}개, 카테고리 조건은 하위 카테고리 포함,
 * 다른 블로그 카테고리는 결과 없음, 버전 조회(id·updated_at)와 본문 조회가 각각 쿼리 1회, 본문 노출 가능 여부 표시.
 */
@JpaRepositoryTest
@Import(FeedItemQueryRepository.class)
class FeedItemQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private FeedItemQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private Blog blog;
    private Category spring;
    private Category boot;
    private Category other;
    private Post p1;
    private Post p2;
    private Post p3;
    private Post p4;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        blog = fx.blog(fx.user("marco"), "marco");
        spring = fx.category(blog, null, "Spring", 0);
        boot = fx.category(blog, spring, "Boot", 0);
        other = fx.category(blog, null, "Other", 1);
        p1 = fx.published(blog, "글1", spring, 1);
        p2 = fx.published(blog, "글2", boot, 2);
        p3 = fx.published(blog, "글3", other, 3);
        p4 = fx.published(blog, "글4", null, 4);
        fx.post(blog, "비공개", spring, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 5);
        fx.post(blog, "임시저장", spring, PostStatus.DRAFT, PostVisibility.PUBLIC, 6);
        fx.post(blog, "휴지통", spring, PostStatus.DELETED, PostVisibility.PUBLIC, 7);
        fx.published(fx.blog(fx.user("third"), "third"), "다른 블로그", null, 8);
        fx.flushAndClear();
    }

    @Test
    void blogFeedHasListablePostsNewestFirstUpToLimit() {
        queryCounter.reset();
        List<FeedVersionRow> versions = repository.findVersions(blog.getId(), null, 3);
        List<FeedItemRow> items = repository.findItems(blog.getId(), null, 3);

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(versions).extracting(FeedVersionRow::id).containsExactly(p4.getId(), p3.getId(), p2.getId());
        assertThat(versions).allSatisfy(v -> assertThat(v.updatedAt()).isNotNull());
        assertThat(items).extracting(FeedItemRow::id).containsExactly(p4.getId(), p3.getId(), p2.getId());
        FeedItemRow second = items.get(1);
        assertThat(second.title()).isEqualTo("글3");
        assertThat(second.contentHtml()).isEqualTo("<p>본문</p>");
        assertThat(second.summary()).isEqualTo("요약 글3");
        assertThat(second.categoryName()).isEqualTo("Other");
        assertThat(second.publishedAt()).isEqualTo(JpaFixtures.T0.plusSeconds(180));
        assertThat(second.bodyVisible()).isTrue();
        assertThat(items.getFirst().categoryName()).isNull();
    }

    @Test
    void categoryFeedIncludesChildCategories() {
        assertThat(repository.findItems(blog.getId(), spring.getId(), 20)).extracting(FeedItemRow::id)
                .containsExactly(p2.getId(), p1.getId());
        assertThat(repository.findVersions(blog.getId(), spring.getId(), 20)).extracting(FeedVersionRow::id)
                .containsExactly(p2.getId(), p1.getId());
        assertThat(repository.findItems(blog.getId(), boot.getId(), 20)).extracting(FeedItemRow::id)
                .containsExactly(p2.getId());
    }

    @Test
    void categoryOfAnotherBlogFindsNothing() {
        Category foreign = fx.category(fx.blog(fx.user("x"), "x"), null, "X", 0);
        fx.flushAndClear();

        assertThat(repository.findItems(blog.getId(), foreign.getId(), 20)).isEmpty();
    }

    @Test
    void suspendedOwnerOrDeletedBlogHasNoItems() {
        Blog suspended = fx.blog(fx.user("suspended"), "s");
        fx.published(suspended, "정지", null, 1);
        TestEntities.with(suspended.getUser(), "status", UserStatus.SUSPENDED);
        Blog deleted = fx.blog(fx.user("del"), "d");
        fx.published(deleted, "삭제", null, 1);
        deleted.delete(NOW);
        fx.flushAndClear();

        assertThat(repository.findItems(suspended.getId(), null, 20)).isEmpty();
        assertThat(repository.findVersions(deleted.getId(), null, 20)).isEmpty();
    }
}
