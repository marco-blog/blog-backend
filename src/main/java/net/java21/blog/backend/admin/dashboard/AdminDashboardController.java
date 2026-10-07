package net.java21.blog.backend.admin.dashboard;

import net.java21.blog.backend.admin.dashboard.dto.AdminDashboardResponse;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 콘솔 대시보드 API(006 contracts/api.md "대시보드"). {@code AdminAccessFilter}가 DB 권한을 확인한 뒤에만 온다.
 * 응답은 로그인 응답이라 {@code Cache-Control: no-store}(Spring Security 기본값).
 */
@RestController
public class AdminDashboardController {

    private final AdminDashboardService service;

    public AdminDashboardController(AdminDashboardService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/admin/dashboard")
    ApiResponse<AdminDashboardResponse> dashboard(@CurrentUser AuthUser admin) {
        return ApiResponse.ok(service.dashboard(admin.userId()));
    }
}
