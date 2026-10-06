package net.java21.blog.backend.category.service;

import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.category.repository.CategoryRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 블로그의 카테고리를 찾는 한 곳. 없거나 다른 블로그의 카테고리면 404 {@code CATEGORY_NOT_FOUND}(contracts/api.md 글 절:
 * {@code categoryId}는 같은 블로그의 카테고리여야 한다). 글 발행·일괄 이동·글 목록 필터·카테고리 관리가 쓴다.
 */
@Component
public class CategoryAccess {

    private final CategoryRepository categoryRepository;

    public CategoryAccess(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /** 쿼리 1회. */
    public Category requireInBlog(Long blogId, Long categoryId) {
        return categoryRepository.findByIdAndBlogId(categoryId, blogId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND,
                        "Category not found: " + categoryId));
    }
}
