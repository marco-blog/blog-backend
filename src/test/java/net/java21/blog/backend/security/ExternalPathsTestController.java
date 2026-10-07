package net.java21.blog.backend.security;

import net.java21.blog.backend.common.api.ApiResponse;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@link ExternalPathsWebMvcTest}용 가짜 엔드포인트(007 경로의 보안 규칙만 확인). */
@TestComponent
@RestController
class ExternalPathsTestController {

    @GetMapping({"/media/external/{key}", "/api/v1/external-posts/{id}/visit"})
    ApiResponse<String> publicRead() {
        return ApiResponse.ok("ok");
    }

    @GetMapping({"/api/v1/me/external-blogs", "/api/v1/me/external-blogs/{id}", "/api/v1/me/external-blogs/{id}/posts"})
    ApiResponse<Long> memberRead(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    @PostMapping({"/api/v1/external-blog-previews", "/api/v1/me/external-blog-verifications",
            "/api/v1/me/external-blog-verifications/{id}/check", "/api/v1/me/external-blogs",
            "/api/v1/external-blogs/{id}/claim"})
    ApiResponse<Long> memberWrite(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    @GetMapping({"/api/v1/admin/external-blogs", "/api/v1/admin/external-blogs/{id}"})
    ApiResponse<String> adminRead() {
        return ApiResponse.ok("ok");
    }

    @PostMapping({"/api/v1/admin/external-blogs", "/api/v1/admin/external-blogs/{id}/approve"})
    ApiResponse<String> adminWrite() {
        return ApiResponse.ok("ok");
    }
}
