package net.java21.blog.backend.tag.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import net.java21.blog.backend.post.dto.PostSummaryResponse;

/** 서비스 전체 태그별 글 한 줄 {@code PostSummary + blogHandle}(GET /tags/{name}/posts). */
public record TaggedPostSummaryResponse(@JsonUnwrapped PostSummaryResponse post, String blogHandle) {
}
