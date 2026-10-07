package net.java21.blog.backend.post.controller;

import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.post.dto.ArchiveMonthResponse;
import net.java21.blog.backend.post.service.ArchiveService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 월별 보관함(004 contracts/api.md). 그 달의 글은 {@code GET /blogs/{handle}/posts?year=&month=}. */
@RestController
public class ArchiveController {

    private final ArchiveService archiveService;

    public ArchiveController(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @GetMapping("/api/v1/blogs/{handle}/archive")
    ApiResponse<List<ArchiveMonthResponse>> archive(@PathVariable String handle) {
        return ApiResponse.ok(archiveService.archive(handle));
    }
}
