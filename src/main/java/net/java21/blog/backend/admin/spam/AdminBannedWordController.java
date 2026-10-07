package net.java21.blog.backend.admin.spam;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.spam.dto.BannedWordRequest;
import net.java21.blog.backend.admin.spam.dto.BannedWordResponse;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import net.java21.blog.backend.spam.BannedWordService;
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

/** 관리자 금칙어(005 contracts/api.md "관리자: 스팸 방어"). 관리자가 아니면 003 {@code AdminAccessFilter}가 404. */
@RestController
@RequestMapping("/api/v1/admin/banned-words")
public class AdminBannedWordController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final BannedWordService service;

    public AdminBannedWordController(BannedWordService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<BannedWordResponse>> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.list(q, PAGES.resolve(page, size, null)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<BannedWordResponse>> create(@CurrentUser AuthUser admin,
            @RequestBody(required = false) BannedWordRequest request, HttpServletRequest http) {
        BannedWordResponse created = service.create(admin.userId(), request, http.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/admin/banned-words/" + created.id()))
                .body(ApiResponse.ok(created));
    }

    @PatchMapping("/{id}")
    ApiResponse<BannedWordResponse> update(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody(required = false) BannedWordRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.update(admin.userId(), id, request, http.getRemoteAddr()));
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@CurrentUser AuthUser admin, @PathVariable long id, HttpServletRequest http) {
        service.delete(admin.userId(), id, http.getRemoteAddr());
        return ApiResponse.ok();
    }
}
