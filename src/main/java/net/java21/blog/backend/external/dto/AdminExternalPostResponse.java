package net.java21.blog.backend.external.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.portal.domain.PortalExclusion;

/** 관리자용 외부 글(007 contracts/api.md AdminExternalPost). */
public record AdminExternalPostResponse(@JsonUnwrapped MyExternalPostResponse base, String guid, String imageUrl,
        List<String> feedTerms, Long classifierTopicId, BigDecimal classifierConfidence, String classifierVersion,
        Excluded excluded, Instant linkCheckedAt) {

    public record Excluded(String reason, AdminRef excludedBy, Instant createdAt) {
    }

    public static AdminExternalPostResponse of(ExternalPost p, boolean verified, PortalExclusion exclusion) {
        return new AdminExternalPostResponse(MyExternalPostResponse.of(p, verified), p.getGuid(), p.getImageUrl(),
                p.getFeedTerms(), p.getClassifierTopic() == null ? null : p.getClassifierTopic().getId(),
                p.getClassifierConfidence(), p.getClassifierVersion(),
                exclusion == null ? null : new Excluded(exclusion.getReason(),
                        new AdminRef(exclusion.getExcludedBy().getId(), exclusion.getExcludedBy().getNickname()),
                        exclusion.getCreatedAt()),
                p.getLinkCheckedAt());
    }
}
