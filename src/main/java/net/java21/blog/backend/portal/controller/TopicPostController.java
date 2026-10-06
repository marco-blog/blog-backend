package net.java21.blog.backend.portal.controller;

import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.portal.dto.PortalCardResponse;
import net.java21.blog.backend.portal.service.TopicPostService;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 주제 페이지 글 목록(003 contracts/api.md 주제 절, FR-078). 비로그인 허용. */
@RestController
public class TopicPostController {

    /** 정렬은 {@code sort=latest|popular}로 받으므로 페이지 해석에는 정렬 필드가 없다. */
    private static final PageRequests PAGES = PageRequests.sortableBy(Sort.unsorted());

    private final TopicPostService topicPostService;

    public TopicPostController(TopicPostService topicPostService) {
        this.topicPostService = topicPostService;
    }

    @GetMapping("/api/v1/topics/{slug}/posts")
    ApiResponse<List<PortalCardResponse>> posts(@PathVariable String slug,
            @RequestParam(required = false) String sort, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.page(topicPostService.posts(slug, sort, PAGES.resolve(page, size, null)));
    }
}
