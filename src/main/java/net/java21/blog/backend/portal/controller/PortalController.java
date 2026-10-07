package net.java21.blog.backend.portal.controller;

import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.portal.dto.LatestSection;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.dto.PortalHomeResponse;
import net.java21.blog.backend.portal.service.PortalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 포털 메인(003 contracts/api.md 포털 메인 절). 비로그인 허용. */
@RestController
public class PortalController {

    private final PortalService portalService;

    public PortalController(PortalService portalService) {
        this.portalService = portalService;
    }

    @GetMapping("/api/v1/portal")
    ApiResponse<PortalHomeResponse> home() {
        return ApiResponse.ok(portalService.home());
    }

    /**
     * 커서 다음의 최신 글 20편(같은 블로그 2편까지). 마지막 묶음이면 {@code nextCursor}가 응답에서 빠진다. 007 {@code source}:
     * {@code all}(기본)·{@code internal}·{@code external}.
     */
    @GetMapping("/api/v1/portal/latest")
    ApiResponse<List<PortalCardResponse>> latest(@RequestParam(required = false) String cursor,
            @RequestParam(required = false) String source) {
        LatestSection section = portalService.latest(cursor, source);
        return ApiResponse.cursor(section.items(), section.nextCursor());
    }
}
