package net.java21.blog.backend.comment.controller;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import net.java21.blog.backend.comment.dto.CommentResponse;
import net.java21.blog.backend.comment.dto.CreateCommentRequest;
import net.java21.blog.backend.comment.dto.GuestCommentPasswordRequest;
import net.java21.blog.backend.comment.dto.UpdateCommentRequest;
import net.java21.blog.backend.comment.service.CommentService;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.common.web.VisitorKeyResolver;
import net.java21.blog.backend.post.service.PostUnlockCookies;
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

/**
 * 댓글(contracts/api.md 댓글 절, FR-027~029). 목록은 글을 볼 수 있는 사람 누구나, 쓰기는 로그인 회원 또는 (블로그가 허용하면) 비회원
 * (004 FR-066). 회원·비회원 판단은 서비스가 한다. 보호 글(004)은 열람 쿠키로 연 뒤에만 쓴다. 비회원 비밀번호 시도 제한의 방문자 키는
 * 쿠키가 있을 때만 쓰고(새로 발급하지 않음) IP와 함께 센다.
 */
@RestController
public class CommentController {

    private final CommentService commentService;
    private final PostUnlockCookies unlockCookies;
    private final VisitorKeyResolver visitorKeys;

    public CommentController(CommentService commentService, PostUnlockCookies unlockCookies,
            VisitorKeyResolver visitorKeys) {
        this.commentService = commentService;
        this.unlockCookies = unlockCookies;
        this.visitorKeys = visitorKeys;
    }

    @GetMapping("/api/v1/posts/{postId}/comments")
    ApiResponse<List<CommentResponse>> list(@CurrentUser(required = false) AuthUser viewer,
            @PathVariable Long postId, HttpServletRequest request) {
        return ApiResponse.ok(commentService.list(postId, userId(viewer), unlockCookies.checker(request)));
    }

    @PostMapping("/api/v1/posts/{postId}/comments")
    ResponseEntity<ApiResponse<CommentResponse>> create(@CurrentUser(required = false) AuthUser user,
            @PathVariable Long postId, @Valid @RequestBody CreateCommentRequest body, HttpServletRequest request) {
        CommentResponse created = commentService.create(userId(user), postId, body, ClientInfo.of(request),
                unlockCookies.checker(request));
        return ResponseEntity.created(URI.create("/api/v1/comments/" + created.id())).body(ApiResponse.ok(created));
    }

    @PatchMapping("/api/v1/comments/{id}")
    ApiResponse<CommentResponse> update(@CurrentUser(required = false) AuthUser user, @PathVariable Long id,
            @Valid @RequestBody UpdateCommentRequest body, HttpServletRequest request) {
        Long userId = userId(user);
        return ApiResponse.ok(commentService.update(userId, id, body, visitorKeys.peek(userId, request),
                request.getRemoteAddr(), unlockCookies.checker(request)));
    }

    /** 비회원 작성자는 본문 {@code { guestPassword }}(004 contracts/api.md "설계 규칙과 다르게 만든 것"). */
    @DeleteMapping("/api/v1/comments/{id}")
    ApiResponse<Void> delete(@CurrentUser(required = false) AuthUser user, @PathVariable Long id,
            @RequestBody(required = false) GuestCommentPasswordRequest body, HttpServletRequest request) {
        Long userId = userId(user);
        commentService.delete(userId, id, body == null ? null : body.guestPassword(),
                visitorKeys.peek(userId, request), request.getRemoteAddr(), unlockCookies.checker(request));
        return ApiResponse.ok();
    }

    /** 비회원 작성자가 비밀번호로 자기 댓글 내용을 본다(비밀 댓글 수정 전, 004). 상태를 바꾸지 않지만 비밀번호를 본문으로 받는다. */
    @PostMapping("/api/v1/comments/{id}/unlock")
    ApiResponse<CommentResponse> unlock(@CurrentUser(required = false) AuthUser user, @PathVariable Long id,
            @RequestBody(required = false) GuestCommentPasswordRequest body, HttpServletRequest request) {
        Long userId = userId(user);
        return ApiResponse.ok(commentService.unlock(userId, id, body == null ? null : body.guestPassword(),
                visitorKeys.peek(userId, request), request.getRemoteAddr(), unlockCookies.checker(request)));
    }

    private static Long userId(AuthUser user) {
        return user == null ? null : user.userId();
    }
}
