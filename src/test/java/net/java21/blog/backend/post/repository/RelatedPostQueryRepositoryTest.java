package net.java21.blog.backend.post.repository;

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
import net.java21.blog.backend.tag.domain.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * 관련 글(T073, FR-068, quickstart #21, research D8): 같은 블로그의 본문 노출 가능 글만, 자기 자신 제외, 점수(겹치는 태그 수 + 같은 카테고리 1)
 * 내림차순·같으면 발행 최신순, 점수 0 제외, 최대 5편, 다른 블로그 글 제외, 쿼리 1회.
 */
@JpaRepositoryTest
@Import(RelatedPostQueryRepository.class)
class RelatedPostQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private RelatedPostQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    private JpaFixtures fx;
    private Blog blog;
    private Category spring;
    private Tag java;
    private Tag jpa;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        blog = fx.blog(fx.user("marco"), "marco");
        spring = fx.category(blog, null, "Spring", 0);
        java = fx.tag("java");
        jpa = fx.tag("jpa");
    }

    @Test
    void ordersByScoreThenNewestAndExcludesUnrelated() {
        // quickstart #21: X(java·jpa), Y(java), Z(jpa·java), W(같은 카테고리만), V(겹침 없음)
        Category other = fx.category(blog, null, "Other", 1);
        Post x = fx.published(blog, "X", spring, 1);
        fx.tagPost(x, java, jpa);
        Post y = fx.published(blog, "Y", other, 2);
        fx.tagPost(y, java);
        Post z = fx.published(blog, "Z", other, 3);
        fx.tagPost(z, jpa, java);
        Post w = fx.published(blog, "W", spring, 4);
        Post v = fx.published(blog, "V", other, 5);
        fx.tagPost(v, fx.tag("docker"));
        fx.flushAndClear();

        queryCounter.reset();
        List<PostSummaryRow> related = repository.findRelated(blog.getId(), x.getId(), spring.getId(), 5);

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(related).extracting(PostSummaryRow::id).containsExactly(z.getId(), w.getId(), y.getId());
        PostSummaryRow first = related.getFirst();
        assertThat(first.title()).isEqualTo("Z");
        assertThat(first.categoryName()).isEqualTo("Other");
        assertThat(first.summary()).isEqualTo("요약 Z");
        assertThat(related).extracting(PostSummaryRow::id).doesNotContain(x.getId(), v.getId());
    }

    @Test
    void sameCategoryAndTagAddUp() {
        Post base = fx.published(blog, "기준", spring, 1);
        fx.tagPost(base, java);
        Post tagOnly = fx.published(blog, "태그만", null, 5);
        fx.tagPost(tagOnly, java);
        Post both = fx.published(blog, "둘 다", spring, 2);
        fx.tagPost(both, java);
        fx.flushAndClear();

        List<PostSummaryRow> related = repository.findRelated(blog.getId(), base.getId(), spring.getId(), 5);

        assertThat(related).extracting(PostSummaryRow::id).containsExactly(both.getId(), tagOnly.getId());
    }

    @Test
    void onlyBodyVisiblePostsOfTheSameBlog() {
        Post base = fx.published(blog, "기준", spring, 1);
        fx.tagPost(base, java);
        Post visible = fx.published(blog, "공개", spring, 2);
        fx.post(blog, "비공개", spring, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 3);
        fx.post(blog, "임시저장", spring, PostStatus.DRAFT, PostVisibility.PUBLIC, 4);
        fx.post(blog, "휴지통", spring, PostStatus.DELETED, PostVisibility.PUBLIC, 5);
        Blog otherBlog = fx.blog(fx.user("third"), "third");
        Post elsewhere = fx.published(otherBlog, "다른 블로그", null, 6);
        fx.tagPost(elsewhere, java);
        fx.flushAndClear();

        List<PostSummaryRow> related = repository.findRelated(blog.getId(), base.getId(), spring.getId(), 5);

        assertThat(related).extracting(PostSummaryRow::id).containsExactly(visible.getId());
    }

    @Test
    void uncategorizedBaseWithoutTagsHasNoRelatedPosts() {
        Post base = fx.published(blog, "기준", null, 1);
        fx.published(blog, "미분류", null, 2);
        fx.published(blog, "카테고리", spring, 3);
        fx.flushAndClear();

        assertThat(repository.findRelated(blog.getId(), base.getId(), null, 5)).isEmpty();
    }

    @Test
    void atMostLimitInOneQuery() {
        Post base = fx.published(blog, "기준", spring, 0);
        fx.tagPost(base, java);
        for (int i = 1; i <= 8; i++) {
            Post p = fx.published(blog, "글 " + i, i % 2 == 0 ? spring : null, i);
            fx.tagPost(p, java);
        }
        fx.flushAndClear();

        queryCounter.reset();
        List<PostSummaryRow> related = repository.findRelated(blog.getId(), base.getId(), spring.getId(), 5);

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(related).extracting(PostSummaryRow::title).containsExactly("글 8", "글 6", "글 4", "글 2", "글 7");
    }
}
