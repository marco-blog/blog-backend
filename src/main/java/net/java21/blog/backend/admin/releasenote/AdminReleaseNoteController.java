package net.java21.blog.backend.admin.releasenote;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.admin.releasenote.dto.AdminReleaseNoteResponse;
import net.java21.blog.backend.admin.releasenote.dto.AdminReleaseNoteSummary;
import net.java21.blog.backend.admin.releasenote.dto.AdminRevisionResponse;
import net.java21.blog.backend.admin.releasenote.dto.PreviewRequest;
import net.java21.blog.backend.admin.releasenote.dto.PreviewResponse;
import net.java21.blog.backend.admin.releasenote.dto.ReleaseNoteWriteRequest;
import net.java21.blog.backend.admin.releasenote.dto.UpdateReleaseNoteRequest;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 릴리스 노트 관리 API 9개(001 contracts "릴리스 노트 관리", 006 FR-167·168). 관리 화면은 006. */
@RestController
@RequestMapping("/api/v1/admin/release-notes")
public class AdminReleaseNoteController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final AdminReleaseNoteService service;

    public AdminReleaseNoteController(AdminReleaseNoteService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<AdminReleaseNoteSummary>> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.list(status, PAGES.resolve(page, size, null)));
    }

    @PostMapping
    ResponseEntity<ApiResponse<AdminReleaseNoteResponse>> create(@CurrentUser AuthUser admin,
            @RequestBody ReleaseNoteWriteRequest request, HttpServletRequest http) {
        AdminReleaseNoteResponse note = service.create(admin.userId(), request, http.getRemoteAddr());
        return ResponseEntity.created(URI.create("/api/v1/admin/release-notes/" + note.id()))
                .body(ApiResponse.ok(note));
    }

    @PostMapping("/preview")
    ApiResponse<PreviewResponse> preview(@RequestBody PreviewRequest request) {
        return ApiResponse.ok(service.preview(request.contentMarkdown()));
    }

    @GetMapping("/{id:\\d+}")
    ApiResponse<AdminReleaseNoteResponse> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping("/{id:\\d+}")
    ApiResponse<AdminReleaseNoteResponse> update(@CurrentUser AuthUser admin, @PathVariable long id,
            @RequestBody UpdateReleaseNoteRequest request, HttpServletRequest http) {
        return ApiResponse.ok(service.update(admin.userId(), id, request, http.getRemoteAddr()));
    }

    @PostMapping("/{id:\\d+}/publish")
    ApiResponse<AdminReleaseNoteResponse> publish(@CurrentUser AuthUser admin, @PathVariable long id,
            HttpServletRequest http) {
        return ApiResponse.ok(service.publish(admin.userId(), id, http.getRemoteAddr()));
    }

    @PostMapping("/{id:\\d+}/unpublish")
    ApiResponse<AdminReleaseNoteResponse> unpublish(@CurrentUser AuthUser admin, @PathVariable long id,
            HttpServletRequest http) {
        return ApiResponse.ok(service.unpublish(admin.userId(), id, http.getRemoteAddr()));
    }

    @DeleteMapping("/{id:\\d+}")
    ApiResponse<Void> delete(@CurrentUser AuthUser admin, @PathVariable long id, HttpServletRequest http) {
        service.delete(admin.userId(), id, http.getRemoteAddr());
        return ApiResponse.ok();
    }

    @GetMapping("/{id:\\d+}/revisions")
    ApiResponse<List<AdminRevisionResponse>> revisions(@PathVariable long id) {
        return ApiResponse.ok(service.revisions(id));
    }

    @GetMapping("/{id:\\d+}/revisions/{revisionNo:\\d{1,9}}")
    ApiResponse<AdminRevisionResponse> revision(@PathVariable long id, @PathVariable int revisionNo) {
        return ApiResponse.ok(service.revision(id, revisionNo));
    }
}
