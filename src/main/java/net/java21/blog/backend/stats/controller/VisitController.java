package net.java21.blog.backend.stats.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.web.VisitorKeyResolver;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import net.java21.blog.backend.stats.service.VisitService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 방문 기록(004 contracts/api.md 방문자 절). front 공개 블로그 레이아웃이 SSR 때 방문자 대신 부른다. 셌는지와 관계없이 200
 * {@code null}이고, 방문자 쿠키가 없으면 001 조회수 API처럼 발급한다.
 */
@RestController
public class VisitController {

    private final VisitService visitService;
    private final VisitorKeyResolver visitorKeys;

    public VisitController(VisitService visitService, VisitorKeyResolver visitorKeys) {
        this.visitService = visitService;
        this.visitorKeys = visitorKeys;
    }

    @PostMapping("/api/v1/blogs/{handle}/visits")
    ApiResponse<Void> visit(@CurrentUser(required = false) AuthUser viewer, @PathVariable String handle,
            HttpServletRequest request, HttpServletResponse response) {
        Long viewerId = viewer == null ? null : viewer.userId();
        visitService.record(handle, viewerId, visitorKeys.resolve(viewerId, request, response),
                request.getHeader(HttpHeaders.USER_AGENT));
        return ApiResponse.ok();
    }
}
