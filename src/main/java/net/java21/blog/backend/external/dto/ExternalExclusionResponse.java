package net.java21.blog.backend.external.dto;

import java.time.Instant;

import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.portal.domain.PortalExclusion;

/** 외부 글 포털 제외(007 contracts/api.md ExternalExclusion). */
public record ExternalExclusionResponse(long externalPostId, String reason, AdminRef excludedBy, Instant createdAt) {

    public static ExternalExclusionResponse of(PortalExclusion e) {
        return new ExternalExclusionResponse(e.getExternalPost().getId(), e.getReason(),
                new AdminRef(e.getExcludedBy().getId(), e.getExcludedBy().getNickname()), e.getCreatedAt());
    }
}
