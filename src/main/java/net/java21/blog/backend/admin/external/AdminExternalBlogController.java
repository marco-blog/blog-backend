package net.java21.blog.backend.admin.external;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.external.dto.AdminCreateExternalBlogRequest;
import net.java21.blog.backend.admin.external.dto.DefaultTopicRequest;
import net.java21.blog.backend.admin.external.dto.ReasonRequest;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.dto.AdminExternalBlogResponse;
import net.java21.blog.backend.external.dto.AdminExternalPostResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 외부 블로그 API(007 contracts/api.md "관리자 API"). 상태 전이는 동작별 POST 하위 경로(contracts "설계 규칙과 다르게 만든 것").
 * 권한은 006 {@code AdminAccessFilter}(요청마다 DB의 현재 권한, 아니면 404).
 */
@RestController
@RequestMapping("/api/v1/admin/external-blogs")
public class AdminExternalBlogController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final AdminExternalBlogService service;

    public AdminExternalBlogController(AdminExternalBlogService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<AdminExternalBlogResponse>> list(@RequestParam(required = false) ExternalBlogStatus status,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.list(status, q, PAGES.resolve(page, size, null)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<AdminExternalBlogResponse>> create(@CurrentUser AuthUser admin,
            @RequestBody AdminCreateExternalBlogRequest request, HttpServletRequest http) {
        AdminExternalBlogResponse created = service.create(admin.userId(), request.feedUrl(), request.defaultTopicId(),
                request.registrationBasis(), http.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/admin/external-blogs/" + created.base().id()))
                .body(ApiResponse.ok(created));
    }

    @GetMapping("/{id}")
    ApiResponse<AdminExternalBlogResponse> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PatchMapping("/{id}")
    ApiResponse<AdminExternalBlogResponse> update(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody DefaultTopicRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.updateDefaultTopic(admin.userId(), id, request.defaultTopicId(),
                http.getRemoteAddr()));
    }

    @PostMapping("/{id}/approve")
    ApiResponse<AdminExternalBlogResponse> approve(@CurrentUser AuthUser admin, @PathVariable long id,
            HttpServletRequest http) {
        return ApiResponse.ok(service.approve(admin.userId(), id, http.getRemoteAddr()));
    }

    @PostMapping("/{id}/reject")
    ApiResponse<AdminExternalBlogResponse> reject(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody ReasonRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.reject(admin.userId(), id, request.reason(), http.getRemoteAddr()));
    }

    @PostMapping("/{id}/pause")
    ApiResponse<AdminExternalBlogResponse> pause(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody(required = false) ReasonRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.pause(admin.userId(), id, request == null ? null : request.reason(),
                http.getRemoteAddr()));
    }

    @PostMapping("/{id}/resume")
    ApiResponse<AdminExternalBlogResponse> resume(@CurrentUser AuthUser admin, @PathVariable long id,
            HttpServletRequest http) {
        return ApiResponse.ok(service.resume(admin.userId(), id, http.getRemoteAddr()));
    }

    @PostMapping("/{id}/block")
    ApiResponse<AdminExternalBlogResponse> block(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody ReasonRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.block(admin.userId(), id, request.reason(), http.getRemoteAddr()));
    }

    @GetMapping("/{id}/posts")
    ApiResponse<List<AdminExternalPostResponse>> posts(@PathVariable long id,
            @RequestParam(required = false) ExternalPostStatus status, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.posts(id, status, PAGES.resolve(page, size, null)));
    }
}
