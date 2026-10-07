package net.java21.blog.backend.report.controller;

import java.net.URI;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.report.dto.CreateReportRequest;
import net.java21.blog.backend.report.dto.ReportCreatedResponse;
import net.java21.blog.backend.report.service.ReportService;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 회원 신고(005 contracts/api.md "신고"). 조회 API는 없고 {@code Location}은 식별용이다. */
@RestController
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/api/v1/reports")
    ResponseEntity<ApiResponse<ReportCreatedResponse>> create(@CurrentUser AuthUser user,
            @RequestBody CreateReportRequest request) {
        ReportCreatedResponse created = reportService.create(user.userId(), request);
        return ResponseEntity.created(URI.create("/api/v1/reports/" + created.id())).body(ApiResponse.ok(created));
    }
}
