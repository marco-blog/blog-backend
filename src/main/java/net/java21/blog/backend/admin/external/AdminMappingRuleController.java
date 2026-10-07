package net.java21.blog.backend.admin.external;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.external.dto.MappingRuleRequest;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.external.dto.TopicMappingRuleResponse;
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

/** 주제 매핑 규칙(007 FR-121, contracts/api.md). 권한은 006 {@code AdminAccessFilter}. */
@RestController
@RequestMapping("/api/v1/admin/topic-mapping-rules")
public class AdminMappingRuleController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final AdminMappingRuleService service;

    public AdminMappingRuleController(AdminMappingRuleService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<TopicMappingRuleResponse>> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.list(q, PAGES.resolve(page, size, null)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<TopicMappingRuleResponse>> create(@CurrentUser AuthUser admin,
            @RequestBody MappingRuleRequest request, HttpServletRequest http) {
        TopicMappingRuleResponse created = service.create(admin.userId(), request.keyword(), request.topicId(),
                request.priority(), http.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/admin/topic-mapping-rules/" + created.id()))
                .body(ApiResponse.ok(created));
    }

    @PatchMapping("/{id}")
    ApiResponse<TopicMappingRuleResponse> update(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody MappingRuleRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.update(admin.userId(), id, request.keyword(), request.topicId(),
                request.priority(), http.getRemoteAddr()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@CurrentUser AuthUser admin, @PathVariable long id, HttpServletRequest http) {
        service.delete(admin.userId(), id, http.getRemoteAddr());
        return ApiResponse.ok(null);
    }
}
