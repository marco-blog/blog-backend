package net.java21.blog.backend.admin.user;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.user.dto.AdminMemberResponse;
import net.java21.blog.backend.admin.user.dto.RoleChangeRequest;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 권한 API(006 FR-105). 목록은 관리자 누구나, 변경은 최고 관리자만(서비스가 DB로 다시 확인해 403). */
@RestController
public class AdminRoleController {

    private final AdminRoleService adminRoleService;

    public AdminRoleController(AdminRoleService adminRoleService) {
        this.adminRoleService = adminRoleService;
    }

    @GetMapping("/api/v1/admin/admins")
    ApiResponse<List<AdminMemberResponse>> admins() {
        return ApiResponse.ok(adminRoleService.admins());
    }

    @PutMapping("/api/v1/admin/users/{id}/role")
    ApiResponse<AdminMemberResponse> changeRole(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody RoleChangeRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(adminRoleService.changeRole(admin.userId(), id, request, httpRequest.getRemoteAddr()));
    }
}
