package net.java21.blog.backend.guestbook.controller;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.common.web.VisitorKeyResolver;
import net.java21.blog.backend.guestbook.dto.GuestPasswordRequest;
import net.java21.blog.backend.guestbook.dto.GuestbookEntryResponse;
import net.java21.blog.backend.guestbook.dto.GuestbookUpdateRequest;
import net.java21.blog.backend.guestbook.dto.GuestbookWriteRequest;
import net.java21.blog.backend.guestbook.service.GuestbookService;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 방명록(004 contracts/api.md 방명록 절). 목록은 누구나, 쓰기·수정·삭제는 회원 또는 비회원(블로그가 허용할 때). 회원·비회원 판단은
 * 서비스가 한다. 비회원 비밀번호 시도 제한의 방문자 키는 쿠키가 있을 때만 쓰고(새로 발급하지 않음) IP와 함께 센다.
 */
@RestController
public class GuestbookController {

    private static final PageRequests GUESTBOOK = PageRequests.sortableBy(Sort.by(Sort.Direction.DESC, "createdAt"));

    private final GuestbookService guestbookService;
    private final VisitorKeyResolver visitorKeys;

    public GuestbookController(GuestbookService guestbookService, VisitorKeyResolver visitorKeys) {
        this.guestbookService = guestbookService;
        this.visitorKeys = visitorKeys;
    }

    @GetMapping("/api/v1/blogs/{handle}/guestbook")
    ApiResponse<List<GuestbookEntryResponse>> list(@CurrentUser(required = false) AuthUser viewer,
            @PathVariable String handle, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(guestbookService.list(handle, userId(viewer), GUESTBOOK.resolve(page, size, null)));
    }

    @PostMapping("/api/v1/blogs/{handle}/guestbook")
    ResponseEntity<ApiResponse<GuestbookEntryResponse>> create(@CurrentUser(required = false) AuthUser user,
            @PathVariable String handle, @Valid @RequestBody GuestbookWriteRequest request,
            HttpServletRequest httpRequest) {
        GuestbookEntryResponse created = guestbookService.create(handle, userId(user), request,
                ClientInfo.of(httpRequest));
        return ResponseEntity.created(URI.create("/api/v1/guestbook-entries/" + created.id()))
                .body(ApiResponse.ok(created));
    }

    @PatchMapping("/api/v1/guestbook-entries/{id}")
    ApiResponse<GuestbookEntryResponse> update(@CurrentUser(required = false) AuthUser user, @PathVariable Long id,
            @Valid @RequestBody GuestbookUpdateRequest request, HttpServletRequest httpRequest) {
        Long userId = userId(user);
        return ApiResponse.ok(guestbookService.update(id, userId, request, visitorKeys.peek(userId, httpRequest),
                httpRequest.getRemoteAddr()));
    }

    @DeleteMapping("/api/v1/guestbook-entries/{id}")
    ApiResponse<Void> delete(@CurrentUser(required = false) AuthUser user, @PathVariable Long id,
            @RequestBody(required = false) GuestPasswordRequest request, HttpServletRequest httpRequest) {
        Long userId = userId(user);
        guestbookService.delete(id, userId, request == null ? null : request.guestPassword(),
                visitorKeys.peek(userId, httpRequest), httpRequest.getRemoteAddr());
        return ApiResponse.ok();
    }

    @PostMapping("/api/v1/guestbook-entries/{id}/unlock")
    ApiResponse<GuestbookEntryResponse> unlock(@CurrentUser(required = false) AuthUser user, @PathVariable Long id,
            @RequestBody(required = false) GuestPasswordRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(guestbookService.unlock(id, request == null ? null : request.guestPassword(),
                visitorKeys.peek(userId(user), httpRequest), httpRequest.getRemoteAddr()));
    }

    private static Long userId(AuthUser user) {
        return user == null ? null : user.userId();
    }
}
