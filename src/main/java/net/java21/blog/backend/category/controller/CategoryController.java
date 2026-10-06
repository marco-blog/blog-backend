package net.java21.blog.backend.category.controller;

import java.net.URI;
import java.util.List;

import net.java21.blog.backend.category.dto.CategoryNode;
import net.java21.blog.backend.category.dto.CategoryOrderItem;
import net.java21.blog.backend.category.dto.CreateCategoryRequest;
import net.java21.blog.backend.category.dto.UpdateCategoryRequest;
import net.java21.blog.backend.category.service.CategoryService;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 카테고리(contracts/api.md 카테고리 절, FR-023·024). 조회는 모두, 쓰기는 블로그 주인만. */
@RestController
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/api/v1/blogs/{handle}/categories")
    ApiResponse<List<CategoryNode>> tree(@PathVariable String handle) {
        return ApiResponse.ok(categoryService.tree(handle));
    }

    @PostMapping("/api/v1/blogs/{handle}/categories")
    ResponseEntity<ApiResponse<CategoryNode>> create(@CurrentUser AuthUser user, @PathVariable String handle,
            @RequestBody CreateCategoryRequest request) {
        CategoryNode node = categoryService.create(user.userId(), handle, request);
        return ResponseEntity.created(URI.create("/api/v1/blogs/" + handle + "/categories/" + node.id()))
                .body(ApiResponse.ok(node));
    }

    @PatchMapping("/api/v1/blogs/{handle}/categories/{id}")
    ApiResponse<CategoryNode> rename(@CurrentUser AuthUser user, @PathVariable String handle, @PathVariable Long id,
            @RequestBody UpdateCategoryRequest request) {
        return ApiResponse.ok(categoryService.rename(user.userId(), handle, id, request));
    }

    @PutMapping("/api/v1/blogs/{handle}/categories/order")
    ApiResponse<List<CategoryNode>> reorder(@CurrentUser AuthUser user, @PathVariable String handle,
            @RequestBody List<CategoryOrderItem> items) {
        return ApiResponse.ok(categoryService.reorder(user.userId(), handle, items));
    }

    @DeleteMapping("/api/v1/blogs/{handle}/categories/{id}")
    ApiResponse<Void> delete(@CurrentUser AuthUser user, @PathVariable String handle, @PathVariable Long id) {
        categoryService.delete(user.userId(), handle, id);
        return ApiResponse.ok();
    }
}
