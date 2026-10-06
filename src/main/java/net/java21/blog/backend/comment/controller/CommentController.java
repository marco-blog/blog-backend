package net.java21.blog.backend.comment.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import net.java21.blog.backend.comment.dto.CommentResponse;
import net.java21.blog.backend.comment.dto.CreateCommentRequest;
import net.java21.blog.backend.comment.dto.UpdateCommentRequest;
import net.java21.blog.backend.comment.service.CommentService;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 댓글(contracts/api.md 댓글 절, FR-027~029). 목록은 글을 볼 수 있는 사람 누구나, 쓰기는 로그인 회원. */
@RestController
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @GetMapping("/api/v1/posts/{postId}/comments")
    ApiResponse<List<CommentResponse>> list(@CurrentUser(required = false) AuthUser viewer,
            @PathVariable Long postId) {
        return ApiResponse.ok(commentService.list(postId, viewer == null ? null : viewer.userId()));
    }

    @PostMapping("/api/v1/posts/{postId}/comments")
    ResponseEntity<ApiResponse<CommentResponse>> create(@CurrentUser AuthUser user, @PathVariable Long postId,
            @Valid @RequestBody CreateCommentRequest request) {
        CommentResponse created = commentService.create(user.userId(), postId, request);
        return ResponseEntity.created(URI.create("/api/v1/comments/" + created.id())).body(ApiResponse.ok(created));
    }

    @PatchMapping("/api/v1/comments/{id}")
    ApiResponse<CommentResponse> update(@CurrentUser AuthUser user, @PathVariable Long id,
            @Valid @RequestBody UpdateCommentRequest request) {
        return ApiResponse.ok(commentService.update(user.userId(), id, request));
    }

    @DeleteMapping("/api/v1/comments/{id}")
    ApiResponse<Void> delete(@CurrentUser AuthUser user, @PathVariable Long id) {
        commentService.delete(user.userId(), id);
        return ApiResponse.ok();
    }
}
