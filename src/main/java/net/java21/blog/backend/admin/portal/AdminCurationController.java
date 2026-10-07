package net.java21.blog.backend.admin.portal;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.portal.dto.CreateCurationRequest;
import net.java21.blog.backend.admin.portal.dto.CurationResponse;
import net.java21.blog.backend.admin.portal.dto.UpdateCurationRequest;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 관리자 추천 API(003 contracts/api.md "관리자: 포털"). 정렬은 상태가 정한다({@code sort} 없음). */
@RestController
@RequestMapping("/api/v1/admin/portal/curations")
public class AdminCurationController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final AdminCurationService service;

    public AdminCurationController(AdminCurationService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<CurationResponse>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.list(status, PAGES.resolve(page, size, null)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<CurationResponse>> create(@CurrentUser AuthUser admin,
            @RequestBody CreateCurationRequest request, HttpServletRequest http) {
        CurationResponse curation = service.create(admin.userId(), request, http.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/admin/portal/curations/" + curation.id()))
                .body(ApiResponse.ok(curation));
    }

    @PatchMapping("/{id}")
    ApiResponse<CurationResponse> update(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody UpdateCurationRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.update(admin.userId(), id, request, http.getRemoteAddr()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@CurrentUser AuthUser admin, @PathVariable long id, HttpServletRequest http) {
        service.delete(admin.userId(), id, http.getRemoteAddr());
        return ApiResponse.ok();
    }
}
