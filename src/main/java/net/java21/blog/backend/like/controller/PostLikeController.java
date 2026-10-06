package net.java21.blog.backend.like.controller;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.like.dto.LikeStateResponse;
import net.java21.blog.backend.like.service.PostLikeService;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 좋아요(002 contracts/api.md 좋아요 절, FR-030). PUT·DELETE 모두 멱등이며 200과 현재 상태·수를 준다
 * (DELETE가 설계 규칙과 다른 이유는 contracts/api.md "설계 규칙과 다르게 만든 것").
 */
@RestController
@RequestMapping("/api/v1/me/likes")
public class PostLikeController {

    private final PostLikeService postLikeService;

    public PostLikeController(PostLikeService postLikeService) {
        this.postLikeService = postLikeService;
    }

    @PutMapping("/{postId}")
    ApiResponse<LikeStateResponse> like(@CurrentUser AuthUser user, @PathVariable Long postId) {
        return ApiResponse.ok(postLikeService.like(user.userId(), postId));
    }

    @DeleteMapping("/{postId}")
    ApiResponse<LikeStateResponse> unlike(@CurrentUser AuthUser user, @PathVariable Long postId) {
        return ApiResponse.ok(postLikeService.unlike(user.userId(), postId));
    }
}
