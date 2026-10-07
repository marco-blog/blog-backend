package net.java21.blog.backend.export.controller;

import java.net.URI;
import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.web.CacheHeaders;
import net.java21.blog.backend.export.dto.BlogExportResponse;
import net.java21.blog.backend.export.service.BlogExportService;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 블로그 백업(004 contracts/api.md "블로그 백업", FR-145). 모두 블로그 주인만. 파일 내려받기는 성공이면 {@code application/zip}
 * 스트림(공통 틀 예외, contracts "설계 규칙과 다르게 만든 것"), 실패는 공통 틀 JSON이다. 응답은 저장하지 않는다({@code no-store}).
 */
@RestController
public class BlogExportController {

    private final BlogExportService exportService;

    public BlogExportController(BlogExportService exportService) {
        this.exportService = exportService;
    }

    /** 202 + {@code Location}. 생성은 정기 작업이 한다. */
    @PostMapping("/api/v1/blogs/{handle}/exports")
    ResponseEntity<ApiResponse<BlogExportResponse>> request(@CurrentUser AuthUser user, @PathVariable String handle) {
        BlogExportResponse created = exportService.request(user.userId(), handle);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/blogs/" + handle + "/exports/" + created.id()))
                .header(HttpHeaders.CACHE_CONTROL, CacheHeaders.NO_STORE)
                .body(ApiResponse.ok(created));
    }

    @GetMapping("/api/v1/blogs/{handle}/exports")
    ResponseEntity<ApiResponse<List<BlogExportResponse>>> list(@CurrentUser AuthUser user,
            @PathVariable String handle) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, CacheHeaders.NO_STORE)
                .body(ApiResponse.ok(exportService.list(user.userId(), handle)));
    }

    /**
     * 파일은 {@link Resource}로 넘겨 메모리에 올리지 않고 스트림으로 복사한다. 요청 스레드에서 쓰므로 비동기 디스패치(보안 문맥이 없는
     * 두 번째 디스패치)가 생기지 않는다.
     */
    @GetMapping("/api/v1/blogs/{handle}/exports/{id}/file")
    ResponseEntity<Resource> file(@CurrentUser AuthUser user, @PathVariable String handle, @PathVariable Long id) {
        BlogExportService.ExportFile file = exportService.file(user.userId(), handle, id);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, CacheHeaders.NO_STORE);
        if (file.size() >= 0) {
            response.contentLength(file.size());
        }
        return response.body(file.resource());
    }
}
