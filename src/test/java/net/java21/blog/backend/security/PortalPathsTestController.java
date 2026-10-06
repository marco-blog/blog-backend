package net.java21.blog.backend.security;

import net.java21.blog.backend.common.api.ApiResponse;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 003 경로의 보안 규칙 확인용 테스트 컨트롤러(T012). 실제 컨트롤러와 경로가 겹치므로 {@link TestComponent}로 스캔에서 빼고
 * {@code PortalPathsWebMvcTest}만 올린다.
 */
@TestComponent
@RestController
class PortalPathsTestController {

    @GetMapping({"/api/v1/topics", "/api/v1/topics/{slug}/posts", "/api/v1/portal", "/api/v1/portal/latest",
            "/api/v1/release-notes", "/api/v1/release-notes/search", "/api/v1/release-notes/{version}",
            "/api/v1/release-notes/{version}/revisions", "/api/v1/release-notes/{version}/revisions/{n}",
            "/api/v1/admin/topics", "/api/v1/admin/portal/curations", "/api/v1/admin/portal/exclusions",
            "/api/v1/admin/settings", "/api/v1/admin/release-notes"})
    ApiResponse<String> read() {
        return ApiResponse.ok("ok");
    }

    @PostMapping({"/api/v1/posts/{id}/read-complete", "/api/v1/admin/portal/curations", "/api/v1/admin/topics"})
    ApiResponse<String> publicPost() {
        return ApiResponse.ok("ok");
    }

    @PostMapping("/api/v1/me/release-notes/seen")
    ApiResponse<Long> seen(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }
}
