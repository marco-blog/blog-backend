package net.java21.blog.backend.admin.user;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import net.java21.blog.backend.admin.user.dto.AdminUserDetail;
import net.java21.blog.backend.admin.user.dto.AdminUserSummary;
import net.java21.blog.backend.admin.user.dto.BlogLimitRequest;
import net.java21.blog.backend.admin.user.dto.BlogLimitResponse;
import net.java21.blog.backend.admin.user.dto.SuspendRequest;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원 관리 API(001 블로그 한도, 005 검색·상세·정지·해제 — 005 contracts/api.md "관리자: 회원", 006 FR-104). {@code /api/v1/admin/**}는
 * {@code AdminAccessFilter}가 DB의 현재 권한을 확인한 뒤에만 여기에 온다. 정지·해제는 토큰 폐기·정지 목록·작업 기록이 따르는 동작이라 POST.
 */
@RestController
public class AdminUserController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final AdminUserService adminUserService;
    private final SuspensionService suspensionService;

    public AdminUserController(AdminUserService adminUserService, SuspensionService suspensionService) {
        this.adminUserService = adminUserService;
        this.suspensionService = suspensionService;
    }

    @GetMapping("/api/v1/admin/users")
    ApiResponse<List<AdminUserSummary>> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String by, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(adminUserService.search(q, by, PAGES.resolve(page, size, null)));
    }

    @GetMapping("/api/v1/admin/users/{id}")
    ApiResponse<AdminUserDetail> detail(@PathVariable long id) {
        return ApiResponse.ok(adminUserService.detail(id));
    }

    @PostMapping("/api/v1/admin/users/{id}/suspend")
    ApiResponse<AdminUserDetail> suspend(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody(required = false) SuspendRequest request, HttpServletRequest httpRequest) {
        suspensionService.suspend(admin.userId(), id, request == null ? null : request.reason(),
                httpRequest.getRemoteAddr());
        return ApiResponse.ok(adminUserService.detail(id));
    }

    @PostMapping("/api/v1/admin/users/{id}/unsuspend")
    ApiResponse<AdminUserDetail> unsuspend(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody(required = false) SuspendRequest request, HttpServletRequest httpRequest) {
        suspensionService.unsuspend(admin.userId(), id, request == null ? null : request.reason(),
                httpRequest.getRemoteAddr());
        return ApiResponse.ok(adminUserService.detail(id));
    }

    /** 요청 IP는 {@code ClientAddressFilter}가 믿는 프록시의 X-Forwarded-For로 정한 방문자 주소다. */
    @PatchMapping("/api/v1/admin/users/{id}/blog-limit")
    ApiResponse<BlogLimitResponse> changeBlogLimit(@CurrentUser AuthUser admin, @PathVariable long id,
            @Valid @RequestBody BlogLimitRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(adminUserService.changeBlogLimit(admin.userId(), id, request,
                httpRequest.getRemoteAddr()));
    }
}
