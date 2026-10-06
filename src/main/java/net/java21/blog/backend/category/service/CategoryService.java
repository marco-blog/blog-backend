package net.java21.blog.backend.category.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.category.dto.CategoryNode;
import net.java21.blog.backend.category.dto.CategoryOrderItem;
import net.java21.blog.backend.category.dto.CreateCategoryRequest;
import net.java21.blog.backend.category.dto.UpdateCategoryRequest;
import net.java21.blog.backend.category.repository.CategoryQueryRepository;
import net.java21.blog.backend.category.repository.CategoryRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그별 2단계 카테고리 관리(T178, FR-023·024, contracts/api.md 카테고리 절). 쓰기는 블로그 주인만({@link BlogAccess}: 주인 아님 403,
 * 삭제된 블로그 404), 다른 블로그의 카테고리는 404 {@code CATEGORY_NOT_FOUND}.
 * <ul>
 *   <li>이름은 앞뒤 공백을 지운 뒤 1~{@value Category#NAME_MAX}자. 같은 부모(최상위 포함) 아래 중복은 409 {@code CATEGORY_NAME_TAKEN}.</li>
 *   <li>부모는 상위 카테고리여야 하고 하위가 있는 카테고리는 하위가 될 수 없다(422 {@code CATEGORY_DEPTH_EXCEEDED}).</li>
 *   <li>삭제하면 소속 글과 하위 카테고리 글을 미분류로 옮기고(집합 UPDATE) 하위 카테고리도 지운다.</li>
 * </ul>
 */
@Service
public class CategoryService {

    private final BlogAccess blogAccess;
    private final CategoryAccess categoryAccess;
    private final CategoryRepository categoryRepository;
    private final CategoryQueryRepository queryRepository;

    public CategoryService(BlogAccess blogAccess, CategoryAccess categoryAccess, CategoryRepository categoryRepository,
            CategoryQueryRepository queryRepository) {
        this.blogAccess = blogAccess;
        this.categoryAccess = categoryAccess;
        this.categoryRepository = categoryRepository;
        this.queryRepository = queryRepository;
    }

    /** 누구나 보는 카테고리 트리(목록 노출 가능 글 수). 쿼리 3회(블로그, 카테고리, 글 수). */
    @Transactional(readOnly = true)
    public List<CategoryNode> tree(String handle) {
        Blog blog = blogAccess.requireVisibleBlog(handle);
        return queryRepository.findTree(blog.getId());
    }

    @Transactional
    public CategoryNode create(long userId, String handle, CreateCategoryRequest request) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        String name = validName(request.name());
        Category parent = null;
        if (request.parentId() != null) {
            parent = categoryAccess.requireInBlog(blog.getId(), request.parentId());
            if (parent.getParent() != null) {
                throw depthExceeded(request.parentId());
            }
        }
        Long parentId = request.parentId();
        if (queryRepository.existsSiblingName(blog.getId(), parentId, name, null)) {
            throw nameTaken(name);
        }
        Category category = new Category(blog, parent, name, queryRepository.nextSortOrder(blog.getId(), parentId));
        try {
            categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            // 같은 이름을 동시에 만들었다: UNIQUE(blog_id, parent_id, name)가 최종 판단한다.
            throw nameTaken(name);
        }
        return new CategoryNode(category.getId(), category.getName(), 0, List.of());
    }

    @Transactional
    public CategoryNode rename(long userId, String handle, Long categoryId, UpdateCategoryRequest request) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        Category category = categoryAccess.requireInBlog(blog.getId(), categoryId);
        if (request.name() != null) {
            String name = validName(request.name());
            Long parentId = category.getParent() == null ? null : category.getParent().getId();
            if (queryRepository.existsSiblingName(blog.getId(), parentId, name, categoryId)) {
                throw nameTaken(name);
            }
            category.rename(name);
            try {
                categoryRepository.flush();
            } catch (DataIntegrityViolationException e) {
                throw nameTaken(name);
            }
        }
        return findNode(queryRepository.findTree(blog.getId()), categoryId);
    }

    /**
     * 순서·부모 변경. 모든 id와 parentId가 이 블로그의 카테고리여야 하고(아니면 404), 바꾼 뒤에도 2단계 규칙과 같은 부모 아래 이름 유일을
     * 지켜야 한다. 요청에 없는 카테고리는 그대로 둔다. 바꾼 뒤의 트리를 돌려준다.
     */
    @Transactional
    public List<CategoryNode> reorder(long userId, String handle, List<CategoryOrderItem> items) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        if (items == null) {
            throw invalid("items", "REQUIRED");
        }
        Set<Long> seen = new HashSet<>();
        for (CategoryOrderItem item : items) {
            if (item == null || item.id() == null) {
                throw invalid("id", "REQUIRED");
            }
            if (item.sortOrder() == null) {
                throw invalid("sortOrder", "REQUIRED");
            }
            if (!seen.add(item.id())) {
                throw invalid("id", "DUPLICATE");
            }
        }
        Map<Long, Category> byId = new HashMap<>();
        for (Category category : categoryRepository.findByBlogId(blog.getId())) {
            byId.put(category.getId(), category);
        }
        Map<Long, Long> parentOf = new HashMap<>();
        byId.values().forEach(c -> parentOf.put(c.getId(), c.getParent() == null ? null : c.getParent().getId()));
        for (CategoryOrderItem item : items) {
            requireKnown(byId, item.id());
            if (item.parentId() != null) {
                requireKnown(byId, item.parentId());
            }
            parentOf.put(item.id(), item.parentId());
        }
        checkDepth(parentOf);
        checkSiblingNames(byId, parentOf);
        for (CategoryOrderItem item : items) {
            Category parent = item.parentId() == null ? null : byId.get(item.parentId());
            byId.get(item.id()).place(parent, item.sortOrder());
        }
        try {
            categoryRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw nameTaken("(order)");
        }
        return queryRepository.findTree(blog.getId());
    }

    /** 삭제(FR-024): 소속 글과 하위 카테고리 글을 미분류로 옮기고 하위 카테고리와 함께 지운다. */
    @Transactional
    public void delete(long userId, String handle, Long categoryId) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        Category category = categoryAccess.requireInBlog(blog.getId(), categoryId);
        List<Long> ids = new ArrayList<>();
        ids.add(categoryId);
        if (category.getParent() == null) {
            ids.addAll(queryRepository.findChildIds(categoryId));
        }
        queryRepository.uncategorize(ids);
        queryRepository.deleteCategories(blog.getId(), ids);
    }

    /** 부모가 있으면 그 부모는 상위여야 하고 자기 자신일 수 없다. */
    private static void checkDepth(Map<Long, Long> parentOf) {
        parentOf.forEach((id, parentId) -> {
            if (parentId != null && (parentId.equals(id) || parentOf.get(parentId) != null)) {
                throw depthExceeded(id);
            }
        });
    }

    /** 같은 부모 아래 이름이 겹치면 409. DB 정렬 규칙처럼 대소문자를 구분하지 않는다. */
    private static void checkSiblingNames(Map<Long, Category> byId, Map<Long, Long> parentOf) {
        Set<String> keys = new HashSet<>();
        for (Category category : byId.values()) {
            String key = parentOf.get(category.getId()) + "/" + category.getName().toLowerCase(Locale.ROOT);
            if (!keys.add(key)) {
                throw nameTaken(category.getName());
            }
        }
    }

    private static void requireKnown(Map<Long, Category> byId, Long id) {
        if (!byId.containsKey(id)) {
            throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found: " + id);
        }
    }

    private static CategoryNode findNode(List<CategoryNode> tree, Long id) {
        for (CategoryNode node : tree) {
            if (node.id().equals(id)) {
                return node;
            }
            for (CategoryNode child : node.children()) {
                if (child.id().equals(id)) {
                    return child;
                }
            }
        }
        throw new BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found: " + id);
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty()) {
            throw invalid("name", "REQUIRED");
        }
        if (name.length() > Category.NAME_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(new FieldError("name", "TOO_LONG", Map.of("max", Category.NAME_MAX))));
        }
        return name;
    }

    private static BusinessException invalid(String field, String code) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                List.of(FieldError.of(field, code)));
    }

    private static BusinessException nameTaken(String name) {
        return new BusinessException(ErrorCode.CATEGORY_NAME_TAKEN, "Category name taken: " + name);
    }

    private static BusinessException depthExceeded(Long id) {
        return new BusinessException(ErrorCode.CATEGORY_DEPTH_EXCEEDED, "Category depth exceeded: " + id);
    }
}
