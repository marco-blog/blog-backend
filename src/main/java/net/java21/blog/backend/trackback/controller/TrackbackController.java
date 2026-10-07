package net.java21.blog.backend.trackback.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.common.web.CacheHeaders;
import net.java21.blog.backend.post.service.PostUnlockCookies;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import net.java21.blog.backend.trackback.dto.ManagedTrackbackResponse;
import net.java21.blog.backend.trackback.dto.TrackbackPingResponse;
import net.java21.blog.backend.trackback.dto.TrackbackResponse;
import net.java21.blog.backend.trackback.service.TrackbackService;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 트랙백 목록·삭제·보낸 기록(005 contracts/api.md "트랙백"). 받기는 {@link TrackbackXmlController}. */
@RestController
public class TrackbackController {

    /** 최신순 고정. */
    private static final PageRequests TRACKBACKS = PageRequests.sortableBy(Sort.unsorted());

    private final TrackbackService trackbackService;
    private final PostUnlockCookies unlockCookies;

    public TrackbackController(TrackbackService trackbackService, PostUnlockCookies unlockCookies) {
        this.trackbackService = trackbackService;
        this.unlockCookies = unlockCookies;
    }

    @GetMapping("/api/v1/posts/{id}/trackbacks")
    ApiResponse<List<TrackbackResponse>> list(@CurrentUser(required = false) AuthUser viewer, @PathVariable Long id,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        return ApiResponse.page(trackbackService.list(id, viewer == null ? null : viewer.userId(),
                unlockCookies.checker(request), TRACKBACKS.resolve(page, size, null)));
    }

    @DeleteMapping("/api/v1/trackbacks/{id}")
    ApiResponse<Void> delete(@CurrentUser AuthUser user, @PathVariable Long id) {
        trackbackService.delete(user.userId(), id);
        return ApiResponse.ok();
    }

    @GetMapping("/api/v1/blogs/{handle}/manage/trackbacks")
    ResponseEntity<ApiResponse<List<ManagedTrackbackResponse>>> managed(@CurrentUser AuthUser user,
            @PathVariable String handle, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CacheHeaders.NO_STORE)
                .body(ApiResponse.page(trackbackService.managed(user.userId(), handle,
                        TRACKBACKS.resolve(page, size, null))));
    }

    @GetMapping("/api/v1/posts/{id}/trackback-pings")
    ResponseEntity<ApiResponse<List<TrackbackPingResponse>>> pings(@CurrentUser AuthUser user,
            @PathVariable Long id) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CacheHeaders.NO_STORE)
                .body(ApiResponse.ok(trackbackService.pings(user.userId(), id)));
    }
}
