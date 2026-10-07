package net.java21.blog.backend.sidebar.controller;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import net.java21.blog.backend.sidebar.dto.SidebarConfigRequest;
import net.java21.blog.backend.sidebar.dto.SidebarViewResponse;
import net.java21.blog.backend.sidebar.service.SidebarService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 블로그 사이드바(004 contracts/api.md 사이드바 절): 공개 보기, 주인 설정 읽기·저장. */
@RestController
public class SidebarController {

    private final SidebarService sidebarService;

    public SidebarController(SidebarService sidebarService) {
        this.sidebarService = sidebarService;
    }

    @GetMapping("/api/v1/blogs/{handle}/sidebar")
    ApiResponse<SidebarViewResponse> view(@PathVariable String handle) {
        return ApiResponse.ok(sidebarService.view(handle));
    }

    @GetMapping("/api/v1/blogs/{handle}/manage/sidebar")
    ApiResponse<SidebarConfigRequest> config(@CurrentUser AuthUser user, @PathVariable String handle) {
        return ApiResponse.ok(sidebarService.config(user.userId(), handle));
    }

    @PutMapping("/api/v1/blogs/{handle}/sidebar")
    ApiResponse<SidebarConfigRequest> save(@CurrentUser AuthUser user, @PathVariable String handle,
            @RequestBody SidebarConfigRequest request) {
        return ApiResponse.ok(sidebarService.save(user.userId(), handle, request));
    }
}
