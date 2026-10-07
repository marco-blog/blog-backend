package net.java21.blog.backend.security;

import net.java21.blog.backend.common.api.ApiResponse;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 005 경로의 보안 규칙 확인용 테스트 컨트롤러(T012). {@code ModerationPathsWebMvcTest}만 올린다. */
@TestComponent
@RestController
class ModerationPathsTestController {

    @GetMapping({"/api/v1/captcha/config", "/api/v1/posts/{id}/trackbacks", "/api/v1/posts/{id}/trackback-pings",
            "/api/v1/blogs/{handle}/manage/trackbacks", "/api/v1/admin/reports", "/api/v1/admin/reports/summary",
            "/api/v1/admin/contents/hidden-posts", "/api/v1/admin/users", "/api/v1/admin/banned-words"})
    ApiResponse<String> read() {
        return ApiResponse.ok("ok");
    }

    @PostMapping({"/api/v1/rights-requests", "/api/v1/reports", "/{handle}/{postId:\\d+}/trackback",
            "/api/v1/admin/reports/{id}/resolve", "/api/v1/admin/users/{id}/suspend", "/api/v1/admin/banned-words"})
    ApiResponse<String> write() {
        return ApiResponse.ok("ok");
    }

    @DeleteMapping({"/api/v1/trackbacks/{id}", "/api/v1/admin/contents/{type}/{id}/hidden"})
    ApiResponse<String> delete() {
        return ApiResponse.ok("ok");
    }
}
