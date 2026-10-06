package net.java21.blog.backend.post.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostListFilter;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.tag.domain.Tag;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 블로그 글 목록의 카테고리·태그 필터(T168, FR-026, AS4): {@code GET /blogs/{handle}/posts?category=&tag=}가 노출 조각과 함께 동작하고
 * 최신순이다. 상위 카테고리는 하위 카테고리 글을 포함한다(tasks.md 결정 4). 목록 + 태그 일괄 조회는 글 수와 무관하게 쿼리 3회.
 */
@JpaRepositoryTest
@Import({PostQueryRepository.class, TagQueryRepository.class})
class PostCategoryTagFilterRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private PostQueryRepository repository;
    @Autowired
    private TagQueryRepository tagQueryRepository;

    private JpaFixtures fx;
    private Blog marco;
    private Category spring;
    private Category boot;
    private Category life;
    private Tag jpaTag;
    private Tag bootTag;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        marco = fx.blog(fx.user("marco"), "marco");
        spring = fx.category(marco, null, "Spring", 0);
        boot = fx.category(marco, spring, "Boot", 0);
        life = fx.category(marco, null, "일상", 1);
        jpaTag = fx.tag("jpa");
        bootTag = fx.tag("spring boot");
    }

    @Test
    void parentCategoryIncludesChildPostsListableOnlyNewestFirst() {
        Post a = fx.published(marco, "스프링", spring, 1);
        Post b = fx.published(marco, "부트", boot, 3);
        fx.published(marco, "일상", life, 2);
        fx.post(marco, "비공개 부트", boot, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 4);
        fx.post(marco, "임시 부트", boot, PostStatus.DRAFT, PostVisibility.PUBLIC, 5);
        fx.published(marco, "미분류", null, 6);
        Blog other = fx.blog(fx.user("other"), "other");
        fx.published(other, "남의 글", null, 7);
        fx.flushAndClear();

        queryCounter.reset();
        Page<PostSummaryRow> parent = repository.findListablePosts(marco.getId(),
                new PostListFilter(spring.getId(), null), PageRequest.of(0, 20));

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(parent.getContent()).extracting(PostSummaryRow::id).containsExactly(b.getId(), a.getId());
        assertThat(parent.getTotalElements()).isEqualTo(2);
        assertThat(parent.getContent()).extracting(PostSummaryRow::categoryName).containsExactly("Boot", "Spring");
        assertThat(ids(new PostListFilter(boot.getId(), null))).containsExactly(b.getId());
        assertThat(ids(PostListFilter.NONE)).hasSize(4);
    }

    @Test
    void tagFilterAndCombinedFilter() {
        Post a = fx.published(marco, "A", boot, 1);
        Post b = fx.published(marco, "B", life, 2);
        Post c = fx.published(marco, "C", spring, 3);
        fx.tagPost(a, jpaTag, bootTag);
        fx.tagPost(b, jpaTag);
        fx.tagPost(c, bootTag);
        fx.tagPost(fx.post(marco, "비공개", spring, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 4), jpaTag);
        fx.flushAndClear();

        assertThat(ids(new PostListFilter(null, "jpa"))).containsExactly(b.getId(), a.getId());
        assertThat(ids(new PostListFilter(null, "spring boot"))).containsExactly(c.getId(), a.getId());
        assertThat(ids(new PostListFilter(spring.getId(), "jpa"))).containsExactly(a.getId());
        assertThat(ids(new PostListFilter(null, "없음"))).isEmpty();
    }

    @Test
    void listWithTagsHasFixedQueryCountRegardlessOfPostCount() {
        for (int i = 0; i < 30; i++) {
            Post p = fx.published(marco, "글 " + i, i % 2 == 0 ? spring : boot, i);
            fx.tagPost(p, jpaTag, bootTag);
        }
        fx.flushAndClear();

        queryCounter.reset();
        Page<PostSummaryRow> page = repository.findListablePosts(marco.getId(), PostListFilter.NONE,
                PageRequest.of(0, 20));
        var tags = tagQueryRepository.findTagNames(page.getContent().stream().map(PostSummaryRow::id).toList());

        assertThat(queryCounter.count()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(20);
        assertThat(tags).hasSize(20).allSatisfy((id, names) -> assertThat(names).containsExactly("jpa", "spring boot"));
        assertThat(page.getContent()).extracting(PostSummaryRow::categoryId).doesNotContainNull();
    }

    private List<Long> ids(PostListFilter filter) {
        return repository.findListablePosts(marco.getId(), filter, PageRequest.of(0, 20)).getContent().stream()
                .map(PostSummaryRow::id).toList();
    }
}
