package net.java21.blog.backend.admin.external;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.external.dto.ReasonRequest;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.external.dto.AdminExternalPostResponse;
import net.java21.blog.backend.external.dto.ExternalExclusionResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 외부 글 조치(007 contracts/api.md): 내림, 포털 제외·해제. 권한은 006 {@code AdminAccessFilter}. */
@RestController
public class AdminExternalPostController {

    private final AdminExternalPostService service;

    public AdminExternalPostController(AdminExternalPostService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/admin/external-posts/{id}/remove")
    ApiResponse<AdminExternalPostResponse> remove(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody ReasonRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.remove(admin.userId(), id, request.reason(), http.getRemoteAddr()));
    }

    @PutMapping("/api/v1/admin/portal/external-exclusions/{externalPostId}")
    ApiResponse<ExternalExclusionResponse> exclude(@CurrentUser AuthUser admin, @PathVariable long externalPostId,
            @RequestBody ReasonRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.exclude(admin.userId(), externalPostId, request.reason(),
                http.getRemoteAddr()));
    }

    @DeleteMapping("/api/v1/admin/portal/external-exclusions/{externalPostId}")
    ApiResponse<Void> unexclude(@CurrentUser AuthUser admin, @PathVariable long externalPostId,
            HttpServletRequest http) {
        service.unexclude(admin.userId(), externalPostId, http.getRemoteAddr());
        return ApiResponse.ok();
    }
}
