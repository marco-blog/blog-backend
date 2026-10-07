package net.java21.blog.backend.security;

import java.util.Map;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.web.CacheHeaders;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link CacheHeadersWebMvcTest}용 컨트롤러. 실제 경로와 겹치므로 {@link TestComponent}로 컴포넌트 스캔에서 빼고 그 테스트만 올린다.
 */
@TestComponent
@RestController
class CacheHeadersTestController {

    @GetMapping({"/api/v1/me", "/api/v1/me/blogs", "/api/v1/blogs/{handle}/manage/posts",
            "/api/v1/blogs/{handle}/posts/drafts/latest", "/api/v1/me/feed", "/api/v1/me/notifications"})
    ApiResponse<Long> authenticatedRead(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    @PatchMapping("/api/v1/me")
    ApiResponse<Long> authenticatedWrite(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    @PostMapping("/api/v1/blogs/{handle}/posts/drafts")
    ApiResponse<String> create(@CurrentUser AuthUser user, @PathVariable String handle) {
        return ApiResponse.ok(handle);
    }

    /** 공개 GET에서 로그인한 사람이 받는 응답(주인에게만 보이는 내용이 섞일 수 있다). */
    @GetMapping("/api/v1/posts/{id}")
    ApiResponse<Map<String, Object>> post(@PathVariable long id, @CurrentUser(required = false) AuthUser user) {
        return ApiResponse.ok(Map.of("id", id));
    }

    /** 이미지처럼 스스로 Cache-Control을 정하는 응답(contracts/api.md 이미지 절). */
    @GetMapping("/media/{key}")
    ResponseEntity<String> media(@PathVariable String key) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable").body(key);
    }

    /** 002 본인 자원 쓰기(좋아요·구독·알림 읽음). */
    @RequestMapping(path = {"/api/v1/me/likes/{postId}", "/api/v1/me/subscriptions/{handle}"},
            method = {RequestMethod.PUT, RequestMethod.DELETE})
    ApiResponse<Long> discoveryWrite(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    /** 003: 릴리스 노트 확인 버전 저장과 관리자 API. */
    @RequestMapping(path = {"/api/v1/me/release-notes/seen", "/api/v1/admin/topics", "/api/v1/admin/settings/{key}",
            "/api/v1/admin/release-notes"}, method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT})
    ApiResponse<Long> portalWrite(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    @PostMapping({"/api/v1/me/notifications/{id}/read", "/api/v1/me/notifications/bulk"})
    ApiResponse<Long> notificationRead(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    /** 보는 사람마다 다른 공개 GET(002 {@code subscribedByMe}): 실제 BlogController처럼 {@code private, no-cache}. */
    @GetMapping("/api/v1/blogs/{handle}")
    ResponseEntity<ApiResponse<String>> blog(@PathVariable String handle,
            @CurrentUser(required = false) AuthUser user) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CacheHeaders.PRIVATE_NO_CACHE)
                .body(ApiResponse.ok(handle));
    }

    /** 피드·사이트맵: 실제 컨트롤러처럼 {@code no-cache}(ETag·Last-Modified로 재검증). */
    @GetMapping({"/{handle}/rss", "/{handle}/atom", "/sitemap.xml", "/sitemap/pages.xml"})
    ResponseEntity<String> xml() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body("<xml/>");
    }

    /** 004 주인 API(통계·백업·차단). 로그인 응답이라 기본 {@code no-store}. */
    @RequestMapping(path = {"/api/v1/blogs/{handle}/manage/stats", "/api/v1/blogs/{handle}/exports",
            "/api/v1/blogs/{handle}/blocks", "/api/v1/blogs/{handle}/blocks/{userId}",
            "/api/v1/posts/{id}/unschedule"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    ApiResponse<Long> blogFeatureOwner(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }

    /** 004 보호 글 열기: 비로그인도 부르고 열람 쿠키를 준다. */
    @PostMapping("/api/v1/posts/{id}/unlock")
    ApiResponse<Long> unlock(@PathVariable long id) {
        return ApiResponse.ok(id);
    }

    /** 004 백업 파일: 실제 BlogExportController처럼 스스로 {@code no-store}를 붙인다. */
    @GetMapping("/api/v1/blogs/{handle}/exports/{id}/file")
    ResponseEntity<byte[]> exportFile(@CurrentUser AuthUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
                .body(new byte[] {'P', 'K'});
    }

    /** 006 관리자 API(대시보드·콘텐츠 검색·정보·작업 기록·관리자 권한). 로그인 응답이라 기본 {@code no-store}. */
    @RequestMapping(path = {"/api/v1/admin/dashboard", "/api/v1/admin/contents/posts", "/api/v1/admin/contents/comments",
            "/api/v1/admin/contents/guestbook-entries", "/api/v1/admin/reserved-handles",
            "/api/v1/admin/service-settings", "/api/v1/admin/audit-logs", "/api/v1/admin/audit-logs/{id}",
            "/api/v1/admin/audit-logs/actions", "/api/v1/admin/admins", "/api/v1/admin/users/{id}/role"},
            method = {RequestMethod.GET, RequestMethod.PUT})
    ApiResponse<Long> adminConsole(@CurrentUser AuthUser user) {
        return ApiResponse.ok(user.userId());
    }
}
