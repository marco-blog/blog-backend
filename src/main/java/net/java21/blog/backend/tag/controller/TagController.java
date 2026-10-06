package net.java21.blog.backend.tag.controller;

import java.util.List;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.tag.dto.BlogTagResponse;
import net.java21.blog.backend.tag.dto.TaggedPostSummaryResponse;
import net.java21.blog.backend.tag.service.TagService;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 태그(contracts/api.md 태그 절, FR-025·026). 모두 비로그인으로 읽을 수 있다. */
@RestController
public class TagController {

    /** 발행 최신순 고정 */
    private static final PageRequests TAGGED_POSTS =
            PageRequests.sortableBy(Sort.by(Sort.Direction.DESC, "publishedAt"));

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping("/api/v1/tags/{name}/posts")
    ApiResponse<List<TaggedPostSummaryResponse>> taggedPosts(@PathVariable String name,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(tagService.taggedPosts(name, TAGGED_POSTS.resolve(page, size, null)));
    }

    @GetMapping("/api/v1/blogs/{handle}/tags")
    ApiResponse<List<BlogTagResponse>> blogTags(@PathVariable String handle) {
        return ApiResponse.ok(tagService.blogTags(handle));
    }
}
