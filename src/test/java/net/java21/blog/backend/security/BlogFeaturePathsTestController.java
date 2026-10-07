package net.java21.blog.backend.security;

import net.java21.blog.backend.common.api.ApiResponse;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 004 경로의 보안 규칙 확인용 테스트 컨트롤러(T012). 실제 컨트롤러와 경로가 겹치므로 {@link TestComponent}로 스캔에서 빼고
 * {@code BlogFeaturePathsWebMvcTest}만 올린다.
 */
@TestComponent
@RestController
class BlogFeaturePathsTestController {

    @GetMapping({"/api/v1/blogs/{handle}/guestbook", "/api/v1/blogs/{handle}/sidebar",
            "/api/v1/blogs/{handle}/archive", "/api/v1/blogs/{handle}/notices",
            "/api/v1/blogs/{handle}/exports", "/api/v1/blogs/{handle}/exports/{id}/file",
            "/api/v1/blogs/{handle}/blocks", "/api/v1/blogs/{handle}/manage/sidebar",
            "/api/v1/blogs/{handle}/manage/stats"})
    ApiResponse<String> read() {
        return ApiResponse.ok("ok");
    }

    @PostMapping({"/api/v1/blogs/{handle}/visits", "/api/v1/blogs/{handle}/guestbook", "/api/v1/posts/{id}/unlock",
            "/api/v1/posts/{id}/comments", "/api/v1/comments/{id}/unlock", "/api/v1/guestbook-entries/{id}/unlock",
            "/api/v1/blogs/{handle}/exports", "/api/v1/blogs/{handle}/blocks"})
    ApiResponse<String> write() {
        return ApiResponse.ok("ok");
    }

    @PatchMapping({"/api/v1/comments/{id}", "/api/v1/guestbook-entries/{id}"})
    ApiResponse<String> patch() {
        return ApiResponse.ok("ok");
    }

    @DeleteMapping({"/api/v1/comments/{id}", "/api/v1/guestbook-entries/{id}"})
    ApiResponse<String> delete() {
        return ApiResponse.ok("ok");
    }
}
