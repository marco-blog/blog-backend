package net.java21.blog.backend.admin.portal;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.portal.dto.ExclusionRequest;
import net.java21.blog.backend.admin.portal.dto.ExclusionResponse;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 포털 제외 API(003 contracts/api.md "관리자: 포털"). {@code PUT}은 멱등 생성·사유 변경으로 200을 준다(설계 규칙 예외,
 * contracts/api.md "설계 규칙과 다르게 만든 것").
 */
@RestController
@RequestMapping("/api/v1/admin/portal/exclusions")
public class AdminExclusionController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final AdminExclusionService service;

    public AdminExclusionController(AdminExclusionService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<ExclusionResponse>> list(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.list(PAGES.resolve(page, size, null)));
    }

    @PutMapping("/{postId}")
    ApiResponse<ExclusionResponse> exclude(@CurrentUser AuthUser admin, @PathVariable long postId,
            @RequestBody ExclusionRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.exclude(admin.userId(), postId, request.reason(), http.getRemoteAddr()));
    }

    @DeleteMapping("/{postId}")
    ApiResponse<Void> unexclude(@CurrentUser AuthUser admin, @PathVariable long postId, HttpServletRequest http) {
        service.unexclude(admin.userId(), postId, http.getRemoteAddr());
        return ApiResponse.ok();
    }
}
