package net.java21.blog.backend.admin.portal.dto;

import java.time.Instant;

import net.java21.blog.backend.admin.portal.repository.ExclusionRow;

/** 포털 제외(003 contracts/api.md {@code Exclusion}). */
public record ExclusionResponse(PostRef post, String reason, AdminRef excludedBy, Instant createdAt,
        Instant updatedAt) {

    public static ExclusionResponse of(ExclusionRow row) {
        return new ExclusionResponse(new PostRef(row.postId(), row.postTitle(), row.blogHandle()), row.reason(),
                new AdminRef(row.excludedById(), row.excludedByNickname()), row.createdAt(), row.updatedAt());
    }
}
