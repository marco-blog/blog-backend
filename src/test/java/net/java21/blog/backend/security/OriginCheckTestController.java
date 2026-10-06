package net.java21.blog.backend.security;

import net.java21.blog.backend.common.api.ApiResponse;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Origin 검사 확인용 테스트 컨트롤러. */
@TestComponent
@RestController
@RequestMapping("/api/v1/origin-test")
class OriginCheckTestController {

    @GetMapping
    ApiResponse<String> read() {
        return ApiResponse.ok("read");
    }

    @PostMapping
    ApiResponse<String> create() {
        return ApiResponse.ok("created");
    }

    @DeleteMapping
    ApiResponse<Void> remove() {
        return ApiResponse.ok();
    }
}
