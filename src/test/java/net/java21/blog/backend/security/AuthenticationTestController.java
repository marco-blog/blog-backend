package net.java21.blog.backend.security;

import java.util.Map;

import net.java21.blog.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 인증 필터·공개 경로·{@link CurrentUser} 확인용 테스트 컨트롤러. 공개 경로 규칙에 맞춰 경로를 흉내 낸다. */
@RestController
class AuthenticationTestController {

    @GetMapping("/api/v1/me")
    ApiResponse<AuthUser> me(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user);
    }

    @PostMapping("/api/v1/blogs/{handle}/posts")
    ApiResponse<Long> write(@PathVariable String handle, @CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    /** 공개 GET: 로그인했으면 회원, 아니면 null. */
    @GetMapping("/api/v1/blogs/{handle}")
    ApiResponse<Map<String, Object>> blog(@PathVariable String handle, @CurrentUser(required = false) AuthUser user) {
        return ApiResponse.ok(Map.of("handle", handle, "viewer", user == null ? "anonymous" : user.userId()));
    }

    /** 로그인 필수인데 공개 경로에 있는 잘못된 조합: 해석기가 401을 준다. */
    @GetMapping("/api/v1/tags/{name}/mine")
    ApiResponse<Long> mineOnPublicPath(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    @GetMapping({"/api/v1/posts/{id}", "/api/v1/posts/{id}/draft", "/api/v1/tags/{name}", "/api/v1/legal/{doc}",
            "/media/{key}", "/v3/api-docs", "/actuator/health"})
    ApiResponse<String> anyPublic() {
        return ApiResponse.ok("ok");
    }
}
