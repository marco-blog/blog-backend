package net.java21.blog.backend.manage.controller;

import java.util.List;

import jakarta.validation.Valid;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.manage.dto.BulkPostRequest;
import net.java21.blog.backend.manage.dto.BulkPostResponse;
import net.java21.blog.backend.manage.dto.DashboardResponse;
import net.java21.blog.backend.manage.dto.ManageCommentResponse;
import net.java21.blog.backend.manage.dto.ManagePostFilter;
import net.java21.blog.backend.manage.service.ManageCommentService;
import net.java21.blog.backend.manage.service.ManageDashboardService;
import net.java21.blog.backend.manage.service.ManagePostService;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블로그 관리 뼈대(contracts/api.md "블로그 관리 뼈대", 006 FR-099~101의 001 범위). 모두 블로그 주인만 쓴다.
 * 휴지통 복구는 글 API의 {@code POST /posts/{id}/restore}다.
 */
@RestController
public class ManageController {

    /** 정렬은 서버가 정한다(최신순, 휴지통은 최근에 버린 순). */
    private static final PageRequests MANAGE_POSTS = PageRequests.sortableBy(Sort.unsorted());

    /** 관리 댓글 목록은 최신순 고정 */
    private static final PageRequests MANAGE_COMMENTS = PageRequests.sortableBy(Sort.unsorted());

    private final ManageDashboardService dashboardService;
    private final ManagePostService postService;
    private final ManageCommentService commentService;

    public ManageController(ManageDashboardService dashboardService, ManagePostService postService,
            ManageCommentService commentService) {
        this.dashboardService = dashboardService;
        this.postService = postService;
        this.commentService = commentService;
    }

    @GetMapping("/api/v1/blogs/{handle}/manage/dashboard")
    ApiResponse<DashboardResponse> dashboard(@CurrentUser AuthUser user, @PathVariable String handle) {
        return ApiResponse.ok(dashboardService.dashboard(user.userId(), handle));
    }

    @GetMapping("/api/v1/blogs/{handle}/manage/posts")
    ApiResponse<List<PostSummaryResponse>> posts(@CurrentUser AuthUser user, @PathVariable String handle,
            @RequestParam(required = false) PostStatus status,
            @RequestParam(required = false) PostVisibility visibility,
            @RequestParam(required = false) Long category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        ManagePostFilter filter = new ManagePostFilter(status, visibility, category, q);
        return ApiResponse.page(
                postService.posts(user.userId(), handle, filter, MANAGE_POSTS.resolve(page, size, null)));
    }

    @PostMapping("/api/v1/blogs/{handle}/manage/posts/bulk")
    ApiResponse<BulkPostResponse> bulk(@CurrentUser AuthUser user, @PathVariable String handle,
            @Valid @RequestBody BulkPostRequest request) {
        return ApiResponse.ok(postService.bulk(user.userId(), handle, request));
    }

    /** 내 블로그 모든 글의 댓글(최신순, 006 FR-099). 지우기는 {@code DELETE /comments/{id}}. */
    @GetMapping("/api/v1/blogs/{handle}/manage/comments")
    ApiResponse<List<ManageCommentResponse>> comments(@CurrentUser AuthUser user, @PathVariable String handle,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(
                commentService.comments(user.userId(), handle, MANAGE_COMMENTS.resolve(page, size, null)));
    }
}
