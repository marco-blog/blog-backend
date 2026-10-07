package net.java21.blog.backend.block.controller;

import java.util.List;

import net.java21.blog.backend.block.dto.BlockedUserResponse;
import net.java21.blog.backend.block.service.BlogBlockService;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블로그 회원 차단(004 contracts/api.md 회원 차단 절, FR-146). 모두 블로그 주인만. 차단 만들기는 경로가 곧 자원이라 {@code PUT}
 * 멱등 생성 200이다(contracts "설계 규칙과 다르게 만든 것"). 로그인 응답이라 Spring Security 기본 {@code no-store}가 붙는다.
 */
@RestController
public class BlogBlockController {

    private static final PageRequests BLOCKS = PageRequests.sortableBy(Sort.by(Sort.Direction.DESC, "createdAt"));

    private final BlogBlockService blockService;

    public BlogBlockController(BlogBlockService blockService) {
        this.blockService = blockService;
    }

    @GetMapping("/api/v1/blogs/{handle}/blocks")
    ApiResponse<List<BlockedUserResponse>> list(@CurrentUser AuthUser user, @PathVariable String handle,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(blockService.list(user.userId(), handle, BLOCKS.resolve(page, size, null)));
    }

    @PutMapping("/api/v1/blogs/{handle}/blocks/{userId}")
    ApiResponse<BlockedUserResponse> block(@CurrentUser AuthUser user, @PathVariable String handle,
            @PathVariable Long userId) {
        return ApiResponse.ok(blockService.block(user.userId(), handle, userId));
    }

    @DeleteMapping("/api/v1/blogs/{handle}/blocks/{userId}")
    ApiResponse<Void> unblock(@CurrentUser AuthUser user, @PathVariable String handle, @PathVariable Long userId) {
        blockService.unblock(user.userId(), handle, userId);
        return ApiResponse.ok();
    }
}
