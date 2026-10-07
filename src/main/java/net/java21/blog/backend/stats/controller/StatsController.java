package net.java21.blog.backend.stats.controller;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import net.java21.blog.backend.stats.dto.VisitStatsResponse;
import net.java21.blog.backend.stats.service.VisitorStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 블로그 통계(004 contracts/api.md, 주인만). */
@RestController
public class StatsController {

    private final VisitorStatsService statsService;

    public StatsController(VisitorStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/api/v1/blogs/{handle}/manage/stats")
    ApiResponse<VisitStatsResponse> stats(@CurrentUser AuthUser user, @PathVariable String handle,
            @RequestParam(required = false) Integer days) {
        return ApiResponse.ok(statsService.stats(user.userId(), handle, days));
    }
}
