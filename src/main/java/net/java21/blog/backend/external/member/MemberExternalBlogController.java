package net.java21.blog.backend.external.member;

import java.net.URI;
import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.external.dto.MyExternalBlogResponse;
import net.java21.blog.backend.external.dto.MyExternalPostResponse;
import net.java21.blog.backend.external.member.dto.ClaimRequest;
import net.java21.blog.backend.external.member.dto.CreateExternalBlogRequest;
import net.java21.blog.backend.external.member.dto.DefaultTopicChangeRequest;
import net.java21.blog.backend.external.member.dto.FeedPreviewResponse;
import net.java21.blog.backend.external.member.dto.PostTopicRequest;
import net.java21.blog.backend.external.member.dto.PreviewRequest;
import net.java21.blog.backend.external.verify.VerificationRequest;
import net.java21.blog.backend.external.verify.VerificationResponse;
import net.java21.blog.backend.external.verify.VerificationService;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원의 외부 블로그 API(007 contracts/api.md "회원 API"). 모두 로그인 회원만(아니면 401). 미리보기는 외부 요청을 보내는 동작이라
 * POST(결과를 저장하지 않음, contracts "설계 규칙과 다르게 만든 것"). 응답은 Spring Security 기본 {@code no-store}.
 */
@RestController
public class MemberExternalBlogController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final PreviewService previewService;
    private final VerificationService verificationService;
    private final MemberExternalBlogService service;
    private final ExternalPostTopicService topicService;

    public MemberExternalBlogController(PreviewService previewService, VerificationService verificationService,
            MemberExternalBlogService service, ExternalPostTopicService topicService) {
        this.previewService = previewService;
        this.verificationService = verificationService;
        this.service = service;
        this.topicService = topicService;
    }

    @PostMapping("/api/v1/external-blog-previews")
    ApiResponse<FeedPreviewResponse> preview(@CurrentUser AuthUser user, @RequestBody PreviewRequest request) {
        return ApiResponse.ok(previewService.preview(user.userId(), request.url()));
    }

    @PostMapping("/api/v1/me/external-blog-verifications")
    ResponseEntity<ApiResponse<VerificationResponse>> issue(@CurrentUser AuthUser user,
            @RequestBody VerificationRequest request) {
        VerificationService.Issued issued = verificationService.issue(user.userId(), request.feedUrl());
        if (!issued.created()) {
            return ResponseEntity.ok(ApiResponse.ok(issued.verification()));
        }
        return ResponseEntity
                .created(URI.create("/api/v1/me/external-blog-verifications/" + issued.verification().id()))
                .body(ApiResponse.ok(issued.verification()));
    }

    @PostMapping("/api/v1/me/external-blog-verifications/{id}/check")
    ApiResponse<VerificationResponse> check(@CurrentUser AuthUser user, @PathVariable long id,
            @RequestBody(required = false) VerificationRequest request) {
        return ApiResponse.ok(verificationService.check(user.userId(), id, request == null ? null : request.feedUrl()));
    }

    @GetMapping("/api/v1/me/external-blogs")
    ApiResponse<List<MyExternalBlogResponse>> list(@CurrentUser AuthUser user) {
        return ApiResponse.ok(service.list(user.userId()));
    }

    @PostMapping("/api/v1/me/external-blogs")
    ResponseEntity<ApiResponse<MyExternalBlogResponse>> create(@CurrentUser AuthUser user,
            @RequestBody CreateExternalBlogRequest request) {
        MyExternalBlogResponse created = service.create(user.userId(), request.feedUrl(), request.defaultTopicId(),
                request.verificationId());
        return ResponseEntity.created(URI.create("/api/v1/me/external-blogs/" + created.id()))
                .body(ApiResponse.ok(created));
    }

    @GetMapping("/api/v1/me/external-blogs/{id}")
    ApiResponse<MyExternalBlogResponse> get(@CurrentUser AuthUser user, @PathVariable long id) {
        return ApiResponse.ok(service.get(user.userId(), id));
    }

    /** 기본 주제 변경(인증된 주인, 007 US3). */
    @PatchMapping("/api/v1/me/external-blogs/{id}")
    ApiResponse<MyExternalBlogResponse> update(@CurrentUser AuthUser user, @PathVariable long id,
            @RequestBody DefaultTopicChangeRequest request) {
        return ApiResponse.ok(topicService.changeDefaultTopic(user.userId(), id, request.defaultTopicId()));
    }

    /** 글 주제 변경(인증된 주인, 출처 OWNER, 007 US3). */
    @PutMapping("/api/v1/me/external-blogs/{id}/posts/{postId}/topic")
    ApiResponse<MyExternalPostResponse> postTopic(@CurrentUser AuthUser user, @PathVariable long id,
            @PathVariable long postId, @RequestBody PostTopicRequest request) {
        return ApiResponse.ok(topicService.changePostTopic(user.userId(), id, postId, request.topicId()));
    }

    @GetMapping("/api/v1/me/external-blogs/{id}/posts")
    ApiResponse<List<MyExternalPostResponse>> posts(@CurrentUser AuthUser user, @PathVariable long id,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.posts(user.userId(), id, PAGES.resolve(page, size, null)));
    }

    @PostMapping("/api/v1/external-blogs/{id}/claim")
    ApiResponse<MyExternalBlogResponse> claim(@CurrentUser AuthUser user, @PathVariable long id,
            @RequestBody ClaimRequest request) {
        return ApiResponse.ok(service.claim(user.userId(), id, request.verificationId()));
    }
}
