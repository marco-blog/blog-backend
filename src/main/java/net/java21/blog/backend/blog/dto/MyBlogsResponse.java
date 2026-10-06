package net.java21.blog.backend.blog.dto;

import java.time.Instant;
import java.util.List;

/** {@code GET /me/blogs}: 삭제하지 않은 내 블로그(만든 순)와 블로그 수·한도. */
public record MyBlogsResponse(List<Item> items, int count, int limit) {

    /** {@code coverImageUrl}은 미디어(US4) 전까지 null. {@code postCount}는 발행된 글 수(공개·비공개). */
    public record Item(String handle, String title, String coverImageUrl, long postCount, Instant createdAt) {
    }
}
