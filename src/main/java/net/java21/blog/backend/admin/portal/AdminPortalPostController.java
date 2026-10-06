package net.java21.blog.backend.admin.portal;

import net.java21.blog.backend.admin.portal.dto.AdminPortalPostResponse;
import net.java21.blog.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 콘솔에서 글 주소·번호로 글을 찾을 때(003 contracts/api.md {@code GET /admin/portal/posts/{id}}). */
@RestController
public class AdminPortalPostController {

    private final AdminExclusionService service;

    public AdminPortalPostController(AdminExclusionService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/admin/portal/posts/{id}")
    ApiResponse<AdminPortalPostResponse> lookup(@PathVariable long id) {
        return ApiResponse.ok(service.lookup(id));
    }
}
