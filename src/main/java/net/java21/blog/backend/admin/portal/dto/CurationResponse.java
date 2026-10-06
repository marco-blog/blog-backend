package net.java21.blog.backend.admin.portal.dto;

import java.time.Instant;

import net.java21.blog.backend.admin.portal.CurationStatus;
import net.java21.blog.backend.admin.portal.repository.CurationRow;

/**
 * 추천(003 contracts/api.md {@code Curation}). {@code portalEligible}이 false인 ACTIVE 추천은 메인에 보이지 않는다(FR-092).
 */
public record CurationResponse(Long id, PostRef post, Instant startsAt, Instant endsAt, int sortOrder,
        CurationStatus status, boolean portalEligible, AdminRef createdBy, Instant createdAt, Instant updatedAt) {

    public static CurationResponse of(CurationRow row, Instant now, boolean portalEligible) {
        return new CurationResponse(row.id(), new PostRef(row.postId(), row.postTitle(), row.blogHandle()),
                row.startsAt(), row.endsAt(), row.sortOrder(), CurationStatus.of(row.startsAt(), row.endsAt(), now),
                portalEligible, new AdminRef(row.createdById(), row.createdByNickname()), row.createdAt(),
                row.updatedAt());
    }
}
