package net.java21.blog.backend.search.controller;

import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.search.dto.SearchPostResponse;
import net.java21.blog.backend.search.service.PostSearchService;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 서비스 전체 글 검색(002 contracts/api.md 검색 절, FR-035). 비로그인도 쓴다. */
@RestController
public class SearchController {

    /** 검색 결과는 발행 최신순 고정(정렬 매개변수 없음, research D4). */
    private static final PageRequests RESULTS = PageRequests.sortableBy(Sort.by(Sort.Direction.DESC, "publishedAt"));

    private final PostSearchService searchService;

    public SearchController(PostSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/api/v1/search/posts")
    ApiResponse<List<SearchPostResponse>> searchPosts(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(searchService.search(q, RESULTS.resolve(page, size, null)));
    }
}
