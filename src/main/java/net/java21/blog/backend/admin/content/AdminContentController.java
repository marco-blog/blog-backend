package net.java21.blog.backend.admin.content;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.moderation.ContentHideService;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.dto.TargetKey;
import net.java21.blog.backend.report.repository.ReportTargetPreviewRepository;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 콘텐츠 숨김(005 contracts/api.md "관리자: 콘텐츠 숨김"). 경로 조각은 {@code posts}·{@code comments}·
 * {@code guestbook-entries}·{@code trackbacks}(그 밖은 404 {@code NOT_FOUND}). 숨김 상태를 자원으로 보아 PUT·DELETE 멱등.
 */
@RestController
public class AdminContentController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());
    private static final Map<String, ReportTargetType> SEGMENTS = Map.of("posts", ReportTargetType.POST,
            "comments", ReportTargetType.COMMENT, "guestbook-entries", ReportTargetType.GUESTBOOK,
            "trackbacks", ReportTargetType.TRACKBACK);

    /** 숨김 사유 {@code { reason }}. */
    public record HideRequest(String reason) {
    }

    private final ContentHideService hideService;
    private final HiddenPostQueryRepository hiddenPosts;
    private final ReportTargetPreviewRepository previews;

    public AdminContentController(ContentHideService hideService, HiddenPostQueryRepository hiddenPosts,
            ReportTargetPreviewRepository previews) {
        this.hideService = hideService;
        this.hiddenPosts = hiddenPosts;
        this.previews = previews;
    }

    @PutMapping("/api/v1/admin/contents/{segment}/{id}/hidden")
    ApiResponse<ReportTargetPreview> hide(@CurrentUser AuthUser admin, @PathVariable String segment,
            @PathVariable long id, @RequestBody(required = false) HideRequest request, HttpServletRequest http) {
        return ApiResponse.ok(hideService.hide(admin.userId(), type(segment), id,
                request == null ? null : request.reason(), http.getRemoteAddr()));
    }

    @DeleteMapping("/api/v1/admin/contents/{segment}/{id}/hidden")
    ApiResponse<ReportTargetPreview> unhide(@CurrentUser AuthUser admin, @PathVariable String segment,
            @PathVariable long id, @RequestBody(required = false) HideRequest request, HttpServletRequest http) {
        return ApiResponse.ok(hideService.unhide(admin.userId(), type(segment), id,
                request == null ? null : request.reason(), http.getRemoteAddr()));
    }

    /** 숨긴 글(숨긴 순서 최신). 쿼리: 목록 1 + 개수 1 + 미리보기 1. */
    @GetMapping("/api/v1/admin/contents/hidden-posts")
    ApiResponse<List<ReportTargetPreview>> hiddenPosts(@RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Page<Long> ids = hiddenPosts.findHiddenPostIds(PAGES.resolve(page, size, null));
        List<TargetKey> keys = ids.getContent().stream().map(id -> new TargetKey(ReportTargetType.POST, id)).toList();
        Map<TargetKey, ReportTargetPreview> found = previews.previews(keys);
        return ApiResponse.page(new PageImpl<>(keys.stream().map(found::get).toList(), ids.getPageable(),
                ids.getTotalElements()));
    }

    private static ReportTargetType type(String segment) {
        ReportTargetType type = SEGMENTS.get(segment);
        if (type == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Unknown content type: " + segment);
        }
        return type;
    }
}
