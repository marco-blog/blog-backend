package net.java21.blog.backend.admin.report;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.report.dto.AssignTargetRequest;
import net.java21.blog.backend.admin.report.dto.ReportDetailResponse;
import net.java21.blog.backend.admin.report.dto.ReportGroupResponse;
import net.java21.blog.backend.admin.report.dto.ReportSummaryResponse;
import net.java21.blog.backend.admin.report.dto.ResolveReportRequest;
import net.java21.blog.backend.admin.report.dto.ResolveReportResponse;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 신고(005 contracts/api.md "관리자: 신고"). {@code AdminAccessFilter}가 관리자만 들인다. 처리는 그 신고 하나가 아니라 같은 대상의
 * 대기 신고 전체를 닫는 동작이라 POST. 응답은 로그인 응답이라 {@code Cache-Control: no-store}(Spring Security 기본값).
 */
@RestController
@RequestMapping("/api/v1/admin/reports")
public class AdminReportController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final AdminReportService service;

    public AdminReportController(AdminReportService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<ReportGroupResponse>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String targetType, @RequestParam(required = false) String channel,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.list(status, targetType, channel, PAGES.resolve(page, size, null)));
    }

    @GetMapping("/summary")
    ApiResponse<ReportSummaryResponse> summary() {
        return ApiResponse.ok(service.summary());
    }

    @GetMapping("/{id}")
    ApiResponse<ReportDetailResponse> detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PatchMapping("/{id}/target")
    ApiResponse<ReportDetailResponse> assignTarget(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody AssignTargetRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.assignTarget(admin.userId(), id, request, http.getRemoteAddr()));
    }

    @PostMapping("/{id}/resolve")
    ApiResponse<ResolveReportResponse> resolve(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody ResolveReportRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.resolve(admin.userId(), id, request, http.getRemoteAddr()));
    }
}
