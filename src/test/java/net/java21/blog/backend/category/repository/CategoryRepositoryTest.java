package net.java21.blog.backend.category.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.category.dto.CategoryNode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 카테고리 트리와 글 수(T165, FR-023·026, tasks.md 결정 4): {@code postCount}는 "목록 노출 가능" 글 수이고 상위는 하위를 포함한다.
 * 카테고리 수와 무관하게 쿼리 2회(카테고리, 글 수 집계). 순서는 {@code sort_order}, 같으면 id.
 */
@JpaRepositoryTest
@Import(CategoryQueryRepository.class)
class CategoryRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private CategoryQueryRepository repository;
    @Autowired
    private CategoryRepository categoryRepository;

    private JpaFixtures fx;
    private Blog marco;
    private Blog other;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        marco = fx.blog(fx.user("marco"), "marco");
        other = fx.blog(fx.user("other"), "other");
    }

    @Test
    void treeWithListablePostCountsInTwoQueriesParentIncludesChildren() {
        Category spring = fx.category(marco, null, "Spring", 1);
        Category boot = fx.category(marco, spring, "Boot", 2);
        Category jpa = fx.category(marco, spring, "JPA", 1);
        Category life = fx.category(marco, null, "일상", 0);
        fx.published(marco, "스프링 글", spring, 1);
        fx.published(marco, "부트 1", boot, 2);
        fx.published(marco, "부트 2", boot, 3);
        fx.post(marco, "비공개 부트", boot, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 4);
        fx.post(marco, "임시 부트", boot, PostStatus.DRAFT, PostVisibility.PUBLIC, 5);
        fx.post(marco, "버린 JPA", jpa, PostStatus.DELETED, PostVisibility.PUBLIC, 6);
        fx.published(marco, "미분류", null, 7);
        Category theirs = fx.category(other, null, "남의 것", 0);
        fx.published(other, "남의 글", theirs, 1);
        fx.flushAndClear();

        queryCounter.reset();
        List<CategoryNode> tree = repository.findTree(marco.getId());

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(tree).extracting(CategoryNode::name).containsExactly("일상", "Spring");
        CategoryNode springNode = tree.get(1);
        assertThat(springNode.postCount()).isEqualTo(3);
        assertThat(springNode.children()).extracting(CategoryNode::name).containsExactly("JPA", "Boot");
        assertThat(springNode.children()).extracting(CategoryNode::postCount).containsExactly(0L, 2L);
        assertThat(springNode.children()).allSatisfy(child -> assertThat(child.children()).isEmpty());
        assertThat(tree.get(0)).isEqualTo(new CategoryNode(life.getId(), "일상", 0, List.of()));
    }

    @Test
    void manyCategoriesStillTwoQueries() {
        for (int i = 0; i < 10; i++) {
            Category parent = fx.category(marco, null, "상위 " + i, i);
            for (int j = 0; j < 3; j++) {
                Category child = fx.category(marco, parent, "하위 " + j, j);
                fx.published(marco, "글 " + i + "-" + j, child, i * 10 + j);
            }
        }
        fx.flushAndClear();

        queryCounter.reset();
        List<CategoryNode> tree = repository.findTree(marco.getId());

        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(tree).hasSize(10).allSatisfy(node -> assertThat(node.postCount()).isEqualTo(3));
    }

    @Test
    void postsOfSuspendedOwnerAreNotCounted() {
        Category spring = fx.category(marco, null, "Spring", 0);
        fx.published(marco, "글", spring, 1);
        User owner = marco.getUser();
        ReflectionTestUtils.setField(owner, "status", UserStatus.SUSPENDED);
        fx.flushAndClear();

        assertThat(repository.findTree(marco.getId()).getFirst().postCount()).isZero();
    }

    @Test
    void siblingNameCheckCoversTopLevelAndExcludesSelf() {
        Category spring = fx.category(marco, null, "Spring", 0);
        Category boot = fx.category(marco, spring, "Boot", 0);
        fx.category(other, null, "일상", 0);
        fx.flushAndClear();

        queryCounter.reset();
        assertThat(repository.existsSiblingName(marco.getId(), null, "Spring", null)).isTrue();
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(repository.existsSiblingName(marco.getId(), null, "Spring", spring.getId())).isFalse();
        assertThat(repository.existsSiblingName(marco.getId(), null, "Boot", null)).isFalse();
        assertThat(repository.existsSiblingName(marco.getId(), spring.getId(), "Boot", null)).isTrue();
        assertThat(repository.existsSiblingName(marco.getId(), spring.getId(), "Boot", boot.getId())).isFalse();
        assertThat(repository.existsSiblingName(marco.getId(), null, "일상", null)).isFalse();
    }

    @Test
    void nextSortOrderAndChildIds() {
        Category spring = fx.category(marco, null, "Spring", 4);
        Category boot = fx.category(marco, spring, "Boot", 7);
        Category jpa = fx.category(marco, spring, "JPA", 2);
        fx.flushAndClear();

        assertThat(repository.nextSortOrder(marco.getId(), null)).isEqualTo(5);
        assertThat(repository.nextSortOrder(marco.getId(), spring.getId())).isEqualTo(8);
        assertThat(repository.nextSortOrder(marco.getId(), boot.getId())).isZero();
        assertThat(repository.findChildIds(spring.getId())).containsExactlyInAnyOrder(boot.getId(), jpa.getId());
        assertThat(categoryRepository.findByIdAndBlogId(spring.getId(), marco.getId())).isPresent();
        assertThat(categoryRepository.findByIdAndBlogId(spring.getId(), other.getId())).isEmpty();
        assertThat(categoryRepository.findByBlogId(marco.getId())).hasSize(3);
    }

    @Test
    void deleteMovesPostsAndDraftsToUncategorizedWithSetBasedStatements() {
        Category spring = fx.category(marco, null, "Spring", 0);
        Category boot = fx.category(marco, spring, "Boot", 0);
        Category keep = fx.category(marco, null, "일상", 1);
        Post a = fx.published(marco, "A", spring, 1);
        Post b = fx.published(marco, "B", boot, 2);
        Post c = fx.post(marco, "C", boot, PostStatus.DELETED, PostVisibility.PUBLIC, 3);
        Post d = fx.published(marco, "D", keep, 4);
        Post e = fx.post(marco, "E", null, PostStatus.DRAFT, PostVisibility.PUBLIC, 5);
        fx.draft(e, boot.getId(), List.of());
        fx.draft(d, keep.getId(), List.of());
        fx.flushAndClear();

        queryCounter.reset();
        List<Long> ids = List.of(spring.getId(), boot.getId());
        long moved = repository.uncategorize(ids);
        long deleted = repository.deleteCategories(marco.getId(), ids);

        assertThat(queryCounter.count()).isEqualTo(4);
        assertThat(moved).isEqualTo(3);
        assertThat(deleted).isEqualTo(2);
        em.clear();
        assertThat(em.find(Post.class, a.getId()).getCategory()).isNull();
        assertThat(em.find(Post.class, b.getId()).getCategory()).isNull();
        assertThat(em.find(Post.class, c.getId()).getCategory()).isNull();
        assertThat(em.find(Post.class, d.getId()).getCategory().getId()).isEqualTo(keep.getId());
        assertThat(em.find(PostDraft.class, e.getId()).getCategoryId()).isNull();
        assertThat(em.find(PostDraft.class, d.getId()).getCategoryId()).isEqualTo(keep.getId());
        assertThat(categoryRepository.findByBlogId(marco.getId())).extracting(Category::getId)
                .containsExactly(keep.getId());
    }
}
