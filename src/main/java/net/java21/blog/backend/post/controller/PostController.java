package net.java21.blog.backend.post.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.common.web.CacheHeaders;
import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.dto.DraftResponse;
import net.java21.blog.backend.post.dto.DraftWriteRequest;
import net.java21.blog.backend.post.dto.LatestDraftResponse;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.dto.PostListFilter;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.post.dto.PublishSettingsRequest;
import net.java21.blog.backend.post.dto.SavedDraftResponse;
import net.java21.blog.backend.post.service.PostDraftService;
import net.java21.blog.backend.post.service.PostPublishService;
import net.java21.blog.backend.post.service.PostService;
import net.java21.blog.backend.post.service.ReadCompleteService;
import net.java21.blog.backend.post.service.RelatedPostService;
import net.java21.blog.backend.post.service.ViewCountService;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 글(contracts/api.md 글 절, FR-013~022, FR-070, FR-084, FR-107, FR-108). 엔티티가 아니라 DTO만 돌려준다. */
@RestController
public class PostController {

    /** 블로그 글 목록은 발행 최신순 고정(정렬 파라미터 없음). */
    private static final PageRequests BLOG_POSTS = PageRequests.sortableBy(Sort.by(Sort.Direction.DESC, "publishedAt"));

    /** 방문자 쿠키 값: front가 발급하는 UUID 등 짧은 토큰만 받는다. */
    private static final Pattern VISITOR_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final PostService postService;
    private final PostDraftService postDraftService;
    private final PostPublishService postPublishService;
    private final ViewCountService viewCountService;
    private final PostsProperties postsProperties;
    private final RelatedPostService relatedPostService;
    private final ReadCompleteService readCompleteService;

    public PostController(PostService postService, PostDraftService postDraftService,
            PostPublishService postPublishService, ViewCountService viewCountService, PostsProperties postsProperties,
            RelatedPostService relatedPostService, ReadCompleteService readCompleteService) {
        this.postService = postService;
        this.readCompleteService = readCompleteService;
        this.relatedPostService = relatedPostService;
        this.postDraftService = postDraftService;
        this.postPublishService = postPublishService;
        this.viewCountService = viewCountService;
        this.postsProperties = postsProperties;
    }

    @GetMapping("/api/v1/blogs/{handle}/posts")
    ApiResponse<List<PostSummaryResponse>> blogPosts(@PathVariable String handle,
            @RequestParam(required = false) Long category, @RequestParam(required = false) String tag,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(postService.blogPosts(handle, new PostListFilter(category, tag),
                BLOG_POSTS.resolve(page, size, null)));
    }

    @PostMapping("/api/v1/blogs/{handle}/posts/drafts")
    ResponseEntity<ApiResponse<SavedDraftResponse>> createDraft(@CurrentUser AuthUser user,
            @PathVariable String handle, @Valid @RequestBody DraftWriteRequest request) {
        SavedDraftResponse saved = postDraftService.create(user.userId(), handle, request);
        return ResponseEntity.created(URI.create("/api/v1/posts/" + saved.id() + "/draft"))
                .body(ApiResponse.ok(saved));
    }

    @GetMapping("/api/v1/blogs/{handle}/posts/drafts/latest")
    ApiResponse<LatestDraftResponse> latestDraft(@CurrentUser AuthUser user, @PathVariable String handle) {
        return ApiResponse.ok(postDraftService.latest(user.userId(), handle));
    }

    /** 요청한 사람에 따라 {@code likedByMe}가 다르므로 공개 GET이지만 {@code Cache-Control: private, no-cache}(002 contracts/api.md). */
    @GetMapping("/api/v1/posts/{id}")
    ResponseEntity<ApiResponse<PostDetailResponse>> detail(@CurrentUser(required = false) AuthUser viewer,
            @PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CacheHeaders.PRIVATE_NO_CACHE)
                .body(ApiResponse.ok(postService.detail(id, viewer == null ? null : viewer.userId())));
    }

    /** 같은 블로그의 관련 글 최대 5편(002 FR-068). 기준 글을 볼 수 없으면 404 {@code POST_NOT_FOUND}. */
    @GetMapping("/api/v1/posts/{id}/related")
    ApiResponse<List<PostSummaryResponse>> related(@CurrentUser(required = false) AuthUser viewer,
            @PathVariable Long id) {
        return ApiResponse.ok(relatedPostService.related(id, viewer == null ? null : viewer.userId()));
    }

    @DeleteMapping("/api/v1/posts/{id}")
    ApiResponse<Void> delete(@CurrentUser AuthUser user, @PathVariable Long id) {
        postService.delete(user.userId(), id);
        return ApiResponse.ok();
    }

    @GetMapping("/api/v1/posts/{id}/draft")
    ApiResponse<DraftResponse> draft(@CurrentUser AuthUser user, @PathVariable Long id) {
        return ApiResponse.ok(postDraftService.get(user.userId(), id));
    }

    @PutMapping("/api/v1/posts/{id}/draft")
    ApiResponse<SavedDraftResponse> saveDraft(@CurrentUser AuthUser user, @PathVariable Long id,
            @Valid @RequestBody DraftWriteRequest request) {
        return ApiResponse.ok(postDraftService.save(user.userId(), id, request));
    }

    @DeleteMapping("/api/v1/posts/{id}/draft")
    ApiResponse<Void> discardDraft(@CurrentUser AuthUser user, @PathVariable Long id) {
        postDraftService.discard(user.userId(), id);
        return ApiResponse.ok();
    }

    @PostMapping("/api/v1/posts/{id}/publish")
    ApiResponse<PostDetailResponse> publish(@CurrentUser AuthUser user, @PathVariable Long id,
            @Valid @RequestBody PublishSettingsRequest request) {
        return ApiResponse.ok(postPublishService.publish(user.userId(), id, request));
    }

    @PostMapping("/api/v1/posts/{id}/restore")
    ApiResponse<PostSummaryResponse> restore(@CurrentUser AuthUser user, @PathVariable Long id) {
        return ApiResponse.ok(postService.restore(user.userId(), id));
    }

    /**
     * 조회수(FR-020). SSR loader가 방문자 쿠키를 실어 부른다. 늘었는지와 관계없이 200 {@code result: null}.
     * 로그인하지 않았고 방문자 쿠키(UUID)가 없으면 새로 만들어 {@code Set-Cookie}로 내려준다(front가 브라우저에 전달).
     */
    @PostMapping("/api/v1/posts/{id}/views")
    ApiResponse<Void> view(@CurrentUser(required = false) AuthUser viewer, @PathVariable Long id,
            HttpServletRequest request, HttpServletResponse response) {
        Long viewerId = viewer == null ? null : viewer.userId();
        viewCountService.record(id, viewerId, visitorKey(viewerId, request, response));
        return ApiResponse.ok();
    }

    /**
     * 끝까지 읽음(003 FR-086). 글 상세가 본문 끝에 닿으면 브라우저가 한 번 보낸다. 셌는지와 관계없이 200 {@code result: null}.
     * 방문자 키와 쿠키 발급은 조회수 API와 같다.
     */
    @PostMapping("/api/v1/posts/{id}/read-complete")
    ApiResponse<Void> readComplete(@CurrentUser(required = false) AuthUser viewer, @PathVariable Long id,
            HttpServletRequest request, HttpServletResponse response) {
        Long viewerId = viewer == null ? null : viewer.userId();
        readCompleteService.record(id, viewerId, visitorKey(viewerId, request, response));
        return ApiResponse.ok();
    }

    /** 중복 판단 키: 회원이면 {@code u:{id}}, 아니면 방문자 쿠키 {@code v:{id}}(없으면 새로 만들어 내려준다). */
    private String visitorKey(Long viewerId, HttpServletRequest request, HttpServletResponse response) {
        String visitorKey;
        if (viewerId != null) {
            visitorKey = "u:" + viewerId;
        } else {
            String visitorId = visitorCookie(request);
            if (visitorId == null) {
                visitorId = UUID.randomUUID().toString();
                ResponseCookie cookie = ResponseCookie.from(postsProperties.visitorCookie(), visitorId)
                        .path("/")
                        .httpOnly(true)
                        .secure(true)
                        .sameSite("Lax")
                        .maxAge(postsProperties.visitorCookieMaxAge())
                        .build();
                response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
            }
            visitorKey = "v:" + visitorId;
        }
        return visitorKey;
    }

    /** 요청의 방문자 쿠키 값. 없거나 형식이 맞지 않으면 null. */
    private String visitorCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (postsProperties.visitorCookie().equals(cookie.getName())
                        && VISITOR_ID.matcher(cookie.getValue()).matches()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
