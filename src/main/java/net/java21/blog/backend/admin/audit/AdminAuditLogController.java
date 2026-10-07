package net.java21.blog.backend.admin.audit;

import java.util.List;

import net.java21.blog.backend.admin.audit.dto.AuditActionListResponse;
import net.java21.blog.backend.admin.audit.dto.AuditLogDetailResponse;
import net.java21.blog.backend.admin.audit.dto.AuditLogEntryResponse;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작업 기록 조회 API(006 contracts/api.md "작업 기록", FR-106). 조회만 있고 수정·삭제 매핑은 없다(PUT·PATCH·DELETE는 405).
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
public class AdminAuditLogController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final AdminAuditLogService service;

    public AdminAuditLogController(AdminAuditLogService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<AuditLogEntryResponse>> list(@CurrentUser AuthUser admin,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Long adminId, @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType, @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String targetKey, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.list(admin.userId(),
                new AdminAuditLogService.Query(from, to, adminId, action, targetType, targetId, targetKey),
                PAGES.resolve(page, size, null)));
    }

    @GetMapping("/{id:\\d+}")
    ApiResponse<AuditLogDetailResponse> detail(@CurrentUser AuthUser admin, @PathVariable long id) {
        return ApiResponse.ok(service.detail(admin.userId(), id));
    }

    @GetMapping("/actions")
    ApiResponse<AuditActionListResponse> actions() {
        return ApiResponse.ok(service.actions());
    }
}
