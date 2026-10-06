package net.java21.blog.backend.blog.controller;

import java.net.URI;

import jakarta.validation.Valid;

import net.java21.blog.backend.blog.dto.BlogResponse;
import net.java21.blog.backend.blog.dto.CreateBlogRequest;
import net.java21.blog.backend.blog.dto.DeleteBlogRequest;
import net.java21.blog.backend.blog.dto.MyBlogsResponse;
import net.java21.blog.backend.blog.dto.UpdateBlogRequest;
import net.java21.blog.backend.blog.service.BlogService;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.web.CacheHeaders;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 블로그(contracts/api.md 블로그 절, FR-010~012, FR-158, FR-159). */
@RestController
public class BlogController {

    private final BlogService blogService;

    public BlogController(BlogService blogService) {
        this.blogService = blogService;
    }

    @GetMapping("/api/v1/me/blogs")
    ApiResponse<MyBlogsResponse> myBlogs(@CurrentUser AuthUser user) {
        return ApiResponse.ok(blogService.myBlogs(user.userId()));
    }

    @PostMapping("/api/v1/blogs")
    ResponseEntity<ApiResponse<BlogResponse>> create(@CurrentUser AuthUser user,
            @Valid @RequestBody CreateBlogRequest request) {
        BlogResponse blog = blogService.create(user.userId(), request);
        return ResponseEntity.created(URI.create("/api/v1/blogs/" + blog.handle())).body(ApiResponse.ok(blog));
    }

    /** 요청한 사람에 따라 {@code subscribedByMe}가 다르므로 공개 GET이지만 {@code Cache-Control: private, no-cache}(002 contracts/api.md). */
    @GetMapping("/api/v1/blogs/{handle}")
    ResponseEntity<ApiResponse<BlogResponse>> get(@CurrentUser(required = false) AuthUser viewer,
            @PathVariable String handle) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CacheHeaders.PRIVATE_NO_CACHE)
                .body(ApiResponse.ok(blogService.get(handle, viewer == null ? null : viewer.userId())));
    }

    @PatchMapping("/api/v1/blogs/{handle}")
    ApiResponse<BlogResponse> update(@CurrentUser AuthUser user, @PathVariable String handle,
            @Valid @RequestBody UpdateBlogRequest request) {
        return ApiResponse.ok(blogService.update(user.userId(), handle, request));
    }

    @DeleteMapping("/api/v1/blogs/{handle}")
    ApiResponse<Void> delete(@CurrentUser AuthUser user, @PathVariable String handle,
            @Valid @RequestBody DeleteBlogRequest request) {
        blogService.delete(user.userId(), handle, request.password());
        return ApiResponse.ok();
    }
}
