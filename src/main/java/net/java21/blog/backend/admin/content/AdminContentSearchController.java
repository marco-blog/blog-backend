package net.java21.blog.backend.admin.content;

import java.util.List;

import net.java21.blog.backend.admin.content.dto.AdminCommentRow;
import net.java21.blog.backend.admin.content.dto.AdminGuestbookRow;
import net.java21.blog.backend.admin.content.dto.AdminPostRow;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 콘텐츠 관리 검색 API(006 contracts/api.md "콘텐츠 관리 검색", FR-102·104). 숨김·해제는 005 {@code AdminContentController}(다른 파일).
 * {@code AdminAccessFilter}가 DB 권한을 확인한 뒤에만 온다. 정렬은 최신 생성순 고정({@code sort} 없음).
 */
@RestController
public class AdminContentSearchController {

    static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final AdminContentSearchService service;

    public AdminContentSearchController(AdminContentSearchService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/admin/contents/posts")
    ApiResponse<List<AdminPostRow>> posts(@RequestParam(required = false) String q,
            @RequestParam(required = false) String handle, @RequestParam(required = false) Long authorId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String visibility,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.posts(q, handle, authorId, status, visibility,
                PAGES.resolve(page, size, null)));
    }

    @GetMapping("/api/v1/admin/contents/comments")
    ApiResponse<List<AdminCommentRow>> comments(@RequestParam(required = false) Long postId,
            @RequestParam(required = false) Long authorId, @RequestParam(required = false) String handle,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.comments(postId, authorId, handle, status, q,
                PAGES.resolve(page, size, null)));
    }

    @GetMapping("/api/v1/admin/contents/guestbook-entries")
    ApiResponse<List<AdminGuestbookRow>> guestbook(@RequestParam(required = false) String handle,
            @RequestParam(required = false) Long authorId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(service.guestbook(handle, authorId, status, q, PAGES.resolve(page, size, null)));
    }
}
