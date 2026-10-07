package net.java21.blog.backend.admin.external;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.external.dto.ConfirmBatchRequest;
import net.java21.blog.backend.admin.external.dto.TopicIdRequest;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.external.domain.ReviewStatus;
import net.java21.blog.backend.external.dto.ClassificationReviewResponse;
import net.java21.blog.backend.external.dto.ClassificationStatsResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 분류 검수와 현황(007 FR-121·122, contracts/api.md "관리자 API"). 권한은 006 {@code AdminAccessFilter}(요청마다 DB의 현재 권한,
 * 아니면 404). 응답은 Spring Security 기본 {@code no-store}.
 */
@RestController
public class AdminClassificationController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final ClassificationReviewService reviewService;
    private final ClassificationStatsService statsService;

    public AdminClassificationController(ClassificationReviewService reviewService,
            ClassificationStatsService statsService) {
        this.reviewService = reviewService;
        this.statsService = statsService;
    }

    @GetMapping("/api/v1/admin/classification-reviews")
    ApiResponse<List<ClassificationReviewResponse>> list(@RequestParam(required = false) ReviewStatus status,
            @RequestParam(required = false) Long externalBlogId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(reviewService.list(status, externalBlogId, PAGES.resolve(page, size, null)));
    }

    @PostMapping("/api/v1/admin/classification-reviews/{id}/confirm")
    ApiResponse<ClassificationReviewResponse> confirm(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody TopicIdRequest request, HttpServletRequest http) {
        return ApiResponse.ok(reviewService.confirm(admin.userId(), id, request.topicId(), http.getRemoteAddr()));
    }

    @PostMapping("/api/v1/admin/classification-reviews/confirm-batch")
    ApiResponse<ClassificationReviewService.BatchResult> confirmBatch(@CurrentUser AuthUser admin,
            @RequestBody ConfirmBatchRequest request, HttpServletRequest http) {
        return ApiResponse.ok(reviewService.confirmBatch(admin.userId(), request.items(), http.getRemoteAddr()));
    }

    @GetMapping("/api/v1/admin/classification-stats")
    ApiResponse<ClassificationStatsResponse> stats() {
        return ApiResponse.ok(statsService.stats());
    }
}
