package net.java21.blog.backend.admin.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import net.java21.blog.backend.admin.user.dto.BlogLimitRequest;
import net.java21.blog.backend.admin.user.dto.BlogLimitResponse;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 회원 관리 API 중 001 범위(contracts/api.md "관리자 API 공통 규칙"). {@code /api/v1/admin/**}는
 * {@code AdminAccessFilter}가 DB의 현재 권한을 확인한 뒤에만 여기에 온다. 나머지 회원 관리 API는 006이 더한다.
 */
@RestController
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    /** 요청 IP는 {@code ClientAddressFilter}가 믿는 프록시의 X-Forwarded-For로 정한 방문자 주소다. */
    @PatchMapping("/api/v1/admin/users/{id}/blog-limit")
    ApiResponse<BlogLimitResponse> changeBlogLimit(@CurrentUser AuthUser admin, @PathVariable long id,
            @Valid @RequestBody BlogLimitRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(adminUserService.changeBlogLimit(admin.userId(), id, request,
                httpRequest.getRemoteAddr()));
    }
}
