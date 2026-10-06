package net.java21.blog.backend.security;

import net.java21.blog.backend.common.api.ApiResponse;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 002 경로의 보안 규칙 확인용 테스트 컨트롤러(T007). 실제 컨트롤러와 경로가 겹치므로 {@link TestComponent}로 스캔에서 빼고
 * {@code DiscoveryPathsWebMvcTest}만 올린다.
 */
@TestComponent
@RestController
class DiscoveryPathsTestController {

    @GetMapping({"/api/v1/search/posts", "/api/v1/posts/{id}/related", "/{handle}/rss", "/{handle}/atom",
            "/{handle}/category/{categoryId}/rss", "/sitemap.xml", "/sitemap/{file}", "/robots.txt"})
    ApiResponse<String> publicRead() {
        return ApiResponse.ok("ok");
    }

    @GetMapping({"/api/v1/me/feed", "/api/v1/me/notifications"})
    ApiResponse<Long> myRead(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    @PutMapping({"/api/v1/me/likes/{postId}", "/api/v1/me/subscriptions/{handle}"})
    ApiResponse<Long> put(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    @DeleteMapping({"/api/v1/me/likes/{postId}", "/api/v1/me/subscriptions/{handle}"})
    ApiResponse<Long> delete(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }
}
