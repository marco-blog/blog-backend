package net.java21.blog.backend.releasenote.controller;

import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.releasenote.ReleaseNoteLanguage;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteDetailResponse;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteListResponse;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteRevisionItem;
import net.java21.blog.backend.releasenote.dto.ReleaseNoteSearchHit;
import net.java21.blog.backend.releasenote.dto.SeenRequest;
import net.java21.blog.backend.releasenote.service.ReleaseNoteQueryService;
import net.java21.blog.backend.releasenote.service.ReleaseNoteSeenService;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 릴리스 노트 독자 API(001 contracts "릴리스 노트", 003 FR-161~166). {@code {version}}은 {@code 숫자.숫자.숫자}만 받아 {@code /search}와
 * 겹치지 않는다(형식이 다르면 매핑되지 않아 404). {@code lang}을 생략하면 {@code Accept-Language}, 그것도 없으면 ko.
 */
@RestController
public class ReleaseNoteController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());
    private static final String VERSION = "{version:\\d+\\.\\d+\\.\\d+}";

    private final ReleaseNoteQueryService queryService;
    private final ReleaseNoteSeenService seenService;

    public ReleaseNoteController(ReleaseNoteQueryService queryService, ReleaseNoteSeenService seenService) {
        this.queryService = queryService;
        this.seenService = seenService;
    }

    @GetMapping("/api/v1/release-notes")
    ApiResponse<ReleaseNoteListResponse> list(@RequestParam(required = false) String lang,
            @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        return ApiResponse.ok(queryService.list(ReleaseNoteLanguage.resolve(lang, acceptLanguage)));
    }

    @GetMapping("/api/v1/release-notes/search")
    ApiResponse<List<ReleaseNoteSearchHit>> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String lang, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        return ApiResponse.page(queryService.search(q, ReleaseNoteLanguage.resolve(lang, acceptLanguage),
                PAGES.resolve(page, size, null)));
    }

    @GetMapping("/api/v1/release-notes/" + VERSION)
    ApiResponse<ReleaseNoteDetailResponse> detail(@PathVariable String version,
            @RequestParam(required = false) String lang,
            @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        return ApiResponse.ok(queryService.detail(version, ReleaseNoteLanguage.resolve(lang, acceptLanguage)));
    }

    @GetMapping("/api/v1/release-notes/" + VERSION + "/revisions")
    ApiResponse<List<ReleaseNoteRevisionItem>> revisions(@PathVariable String version) {
        return ApiResponse.ok(queryService.revisions(version));
    }

    @GetMapping("/api/v1/release-notes/" + VERSION + "/revisions/{revisionNo:\\d{1,9}}")
    ApiResponse<ReleaseNoteDetailResponse> revision(@PathVariable String version, @PathVariable int revisionNo,
            @RequestParam(required = false) String lang,
            @RequestHeader(name = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
        return ApiResponse.ok(queryService.revision(version, revisionNo,
                ReleaseNoteLanguage.resolve(lang, acceptLanguage)));
    }

    /** 배너 닫기·버전 페이지 열기. 성공은 200 + {@code result: null}(더 낮은 버전이어도 같음). */
    @PostMapping("/api/v1/me/release-notes/seen")
    ApiResponse<Void> seen(@CurrentUser AuthUser user, @RequestBody SeenRequest request) {
        seenService.markSeen(user.userId(), request.version());
        return ApiResponse.ok();
    }
}
