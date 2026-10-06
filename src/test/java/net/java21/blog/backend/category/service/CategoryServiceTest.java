package net.java21.blog.backend.category.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.category.dto.CategoryNode;
import net.java21.blog.backend.category.dto.CategoryOrderItem;
import net.java21.blog.backend.category.dto.CreateCategoryRequest;
import net.java21.blog.backend.category.dto.UpdateCategoryRequest;
import net.java21.blog.backend.category.repository.CategoryQueryRepository;
import net.java21.blog.backend.category.repository.CategoryRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.TestEntities;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 카테고리 관리(T164, FR-023·024, AS1·2): 이름 1~50자, 같은 부모 아래 이름 중복 409(최상위 포함), 부모의 부모가 있으면 422,
 * 이름·순서 변경(모든 id가 이 블로그 것, 깊이 규칙 유지), 삭제 시 소속·하위 글을 미분류로 옮기고 하위도 삭제, 다른 블로그 카테고리 404,
 * 주인만.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private BlogRepository blogRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private CategoryQueryRepository queryRepository;

    private CategoryService service;
    private Blog blog;
    private Category spring;
    private Category boot;
    private Category life;

    @BeforeEach
    void setUp() {
        BlogAccess blogAccess = new BlogAccess(blogRepository);
        service = new CategoryService(blogAccess, new CategoryAccess(categoryRepository), categoryRepository,
                queryRepository);
        blog = TestEntities.blog(10L, TestEntities.user(1L), "marco");
        lenient().when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        spring = category(1L, null, "Spring", 0);
        boot = category(2L, spring, "Boot", 0);
        life = category(3L, null, "일상", 1);
        for (Category c : List.of(spring, boot, life)) {
            lenient().when(categoryRepository.findByIdAndBlogId(c.getId(), 10L)).thenReturn(Optional.of(c));
        }
        lenient().when(categoryRepository.findByIdAndBlogId(eq(99L), anyLong())).thenReturn(Optional.empty());
    }

    @Test
    void treeIsPublic() {
        List<CategoryNode> tree = List.of(new CategoryNode(1L, "Spring", 2, List.of()));
        when(queryRepository.findTree(10L)).thenReturn(tree);

        assertThat(service.tree("marco")).isEqualTo(tree);
    }

    @Test
    void createTopLevelAtEndOfSiblings() {
        when(queryRepository.existsSiblingName(10L, null, "Java", null)).thenReturn(false);
        when(queryRepository.nextSortOrder(10L, null)).thenReturn(2);
        when(categoryRepository.saveAndFlush(any())).thenAnswer(inv -> TestEntities.with(inv.getArgument(0), "id", 7L));

        CategoryNode node = service.create(1L, "marco", new CreateCategoryRequest("  Java ", null));

        assertThat(node).isEqualTo(new CategoryNode(7L, "Java", 0, List.of()));
        ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getParent()).isNull();
        assertThat(saved.getValue().getSortOrder()).isEqualTo(2);
        assertThat(saved.getValue().getBlog()).isSameAs(blog);
    }

    @Test
    void createChildUnderTopLevel() {
        when(queryRepository.existsSiblingName(10L, 1L, "JPA", null)).thenReturn(false);
        when(categoryRepository.saveAndFlush(any())).thenAnswer(inv -> TestEntities.with(inv.getArgument(0), "id", 8L));

        CategoryNode node = service.create(1L, "marco", new CreateCategoryRequest("JPA", 1L));

        assertThat(node.id()).isEqualTo(8L);
        ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getParent()).isSameAs(spring);
    }

    @Test
    void createRejectsThirdLevelDuplicateNameBlankNameAndOtherBlogsParent() {
        assertCode(() -> service.create(1L, "marco", new CreateCategoryRequest("깊음", 2L)),
                ErrorCode.CATEGORY_DEPTH_EXCEEDED);
        when(queryRepository.existsSiblingName(10L, null, "Spring", null)).thenReturn(true);
        assertCode(() -> service.create(1L, "marco", new CreateCategoryRequest("Spring", null)),
                ErrorCode.CATEGORY_NAME_TAKEN);
        assertCode(() -> service.create(1L, "marco", new CreateCategoryRequest("   ", null)),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(1L, "marco", new CreateCategoryRequest("가".repeat(51), null)),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create(1L, "marco", new CreateCategoryRequest("새", 99L)),
                ErrorCode.CATEGORY_NOT_FOUND);
        verify(categoryRepository, never()).saveAndFlush(any());
    }

    @Test
    void createRaceOnUniqueKeyIsNameTaken() {
        when(queryRepository.existsSiblingName(10L, 1L, "Boot", null)).thenReturn(false);
        when(categoryRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));

        assertCode(() -> service.create(1L, "marco", new CreateCategoryRequest("Boot", 1L)),
                ErrorCode.CATEGORY_NAME_TAKEN);
    }

    @Test
    void onlyOwnerManages() {
        assertCode(() -> service.create(2L, "marco", new CreateCategoryRequest("Java", null)), ErrorCode.FORBIDDEN);
        assertCode(() -> service.delete(2L, "marco", 1L), ErrorCode.FORBIDDEN);
        when(blogRepository.findByHandleWithOwner("gone")).thenReturn(Optional.empty());
        assertCode(() -> service.tree("gone"), ErrorCode.BLOG_NOT_FOUND);
    }

    @Test
    void renameChecksSiblingsExcludingSelfAndReturnsNodeWithCount() {
        when(queryRepository.existsSiblingName(10L, 1L, "Spring Boot", 2L)).thenReturn(false);
        when(queryRepository.findTree(10L)).thenReturn(List.of(new CategoryNode(1L, "Spring", 5,
                List.of(new CategoryNode(2L, "Spring Boot", 3, List.of())))));

        CategoryNode node = service.rename(1L, "marco", 2L, new UpdateCategoryRequest(" Spring Boot "));

        assertThat(boot.getName()).isEqualTo("Spring Boot");
        verify(categoryRepository).flush();
        assertThat(node).isEqualTo(new CategoryNode(2L, "Spring Boot", 3, List.of()));
    }

    @Test
    void renameWithoutNameReturnsCurrentNodeAndRejectsTakenOrBlankName() {
        when(queryRepository.findTree(10L)).thenReturn(List.of(new CategoryNode(1L, "Spring", 5, List.of())));
        assertThat(service.rename(1L, "marco", 1L, new UpdateCategoryRequest(null)).postCount()).isEqualTo(5);

        when(queryRepository.existsSiblingName(10L, null, "일상", 1L)).thenReturn(true);
        assertCode(() -> service.rename(1L, "marco", 1L, new UpdateCategoryRequest("일상")),
                ErrorCode.CATEGORY_NAME_TAKEN);
        assertCode(() -> service.rename(1L, "marco", 1L, new UpdateCategoryRequest(" ")),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.rename(1L, "marco", 99L, new UpdateCategoryRequest("x")),
                ErrorCode.CATEGORY_NOT_FOUND);
        assertThat(spring.getName()).isEqualTo("Spring");
    }

    @Test
    void renameRaceIsNameTaken() {
        when(queryRepository.existsSiblingName(10L, null, "Java", 3L)).thenReturn(false);
        doThrow(new DataIntegrityViolationException("uk")).when(categoryRepository).flush();

        assertCode(() -> service.rename(1L, "marco", 3L, new UpdateCategoryRequest("Java")),
                ErrorCode.CATEGORY_NAME_TAKEN);
    }

    @Test
    void reorderMovesAndSortsWithinDepthRule() {
        when(categoryRepository.findByBlogId(10L)).thenReturn(new ArrayList<>(List.of(spring, boot, life)));
        List<CategoryNode> tree = List.of(new CategoryNode(3L, "일상", 0, List.of()));
        when(queryRepository.findTree(10L)).thenReturn(tree);

        List<CategoryNode> result = service.reorder(1L, "marco", List.of(
                new CategoryOrderItem(3L, null, 0),
                new CategoryOrderItem(1L, null, 1),
                new CategoryOrderItem(2L, 3L, 0)));

        assertThat(result).isEqualTo(tree);
        assertThat(boot.getParent()).isSameAs(life);
        assertThat(life.getSortOrder()).isZero();
        assertThat(spring.getSortOrder()).isEqualTo(1);
        verify(categoryRepository).flush();
    }

    @Test
    void reorderRejectsDepthViolations() {
        when(categoryRepository.findByBlogId(10L)).thenReturn(List.of(spring, boot, life));

        // 하위가 있는 Spring을 일상 아래로 → 3단계
        assertCode(() -> service.reorder(1L, "marco", List.of(new CategoryOrderItem(1L, 3L, 0))),
                ErrorCode.CATEGORY_DEPTH_EXCEEDED);
        // 일상을 Boot(하위) 아래로
        assertCode(() -> service.reorder(1L, "marco", List.of(new CategoryOrderItem(3L, 2L, 0))),
                ErrorCode.CATEGORY_DEPTH_EXCEEDED);
        // 자기 자신 아래로
        assertCode(() -> service.reorder(1L, "marco", List.of(new CategoryOrderItem(3L, 3L, 0))),
                ErrorCode.CATEGORY_DEPTH_EXCEEDED);
        assertThat(spring.getParent()).isNull();
        assertThat(life.getParent()).isNull();
    }

    @Test
    void reorderRejectsForeignIdsDuplicatesMissingValuesAndNameClash() {
        when(categoryRepository.findByBlogId(10L)).thenReturn(List.of(spring, boot, life));

        assertCode(() -> service.reorder(1L, "marco", List.of(new CategoryOrderItem(99L, null, 0))),
                ErrorCode.CATEGORY_NOT_FOUND);
        assertCode(() -> service.reorder(1L, "marco", List.of(new CategoryOrderItem(2L, 99L, 0))),
                ErrorCode.CATEGORY_NOT_FOUND);
        assertCode(() -> service.reorder(1L, "marco",
                List.of(new CategoryOrderItem(1L, null, 0), new CategoryOrderItem(1L, null, 1))),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.reorder(1L, "marco", List.of(new CategoryOrderItem(null, null, 0))),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.reorder(1L, "marco", List.of(new CategoryOrderItem(1L, null, null))),
                ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.reorder(1L, "marco", null), ErrorCode.VALIDATION_FAILED);
        Category springUnderLife = category(4L, life, "spring", 0);
        when(categoryRepository.findByBlogId(10L)).thenReturn(List.of(spring, boot, life, springUnderLife));
        // 같은 이름(대소문자만 다름)이 같은 부모 아래로 모이면 409
        assertCode(() -> service.reorder(1L, "marco", List.of(new CategoryOrderItem(4L, null, 3))),
                ErrorCode.CATEGORY_NAME_TAKEN);
    }

    @Test
    void reorderRaceIsNameTaken() {
        when(categoryRepository.findByBlogId(10L)).thenReturn(List.of(spring, boot, life));
        doThrow(new DataIntegrityViolationException("uk")).when(categoryRepository).flush();

        assertCode(() -> service.reorder(1L, "marco", List.of(new CategoryOrderItem(3L, null, 5))),
                ErrorCode.CATEGORY_NAME_TAKEN);
    }

    @Test
    void deleteTopLevelMovesOwnAndChildPostsToUncategorizedThenDeletesChildren() {
        when(queryRepository.findChildIds(1L)).thenReturn(List.of(2L));

        service.delete(1L, "marco", 1L);

        InOrder order = inOrder(queryRepository);
        order.verify(queryRepository).uncategorize(List.of(1L, 2L));
        order.verify(queryRepository).deleteCategories(10L, List.of(1L, 2L));
    }

    @Test
    void deleteChildOnlyTouchesItselfAndOtherBlogIs404() {
        service.delete(1L, "marco", 2L);

        verify(queryRepository).uncategorize(List.of(2L));
        verify(queryRepository).deleteCategories(10L, List.of(2L));
        verify(queryRepository, never()).findChildIds(anyLong());

        assertCode(() -> service.delete(1L, "marco", 99L), ErrorCode.CATEGORY_NOT_FOUND);
    }

    @Test
    void categoryAccessRequiresSameBlog() {
        CategoryAccess access = new CategoryAccess(categoryRepository);
        assertThat(access.requireInBlog(10L, 1L)).isSameAs(spring);
        assertCode(() -> access.requireInBlog(10L, 99L), ErrorCode.CATEGORY_NOT_FOUND);
    }

    private Category category(long id, Category parent, String name, int sortOrder) {
        return TestEntities.with(new Category(blog, parent, name, sortOrder), "id", id);
    }

    private static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(code);
    }
}
