package net.java21.blog.backend.external.dto;

import java.time.Instant;

import net.java21.blog.backend.admin.portal.dto.AdminRef;
import net.java21.blog.backend.external.domain.TopicMappingRule;

/** 주제 매핑 규칙(007 contracts/api.md TopicMappingRule). */
public record TopicMappingRuleResponse(long id, String keyword, long topicId, int priority, AdminRef createdBy,
        Instant createdAt, Instant updatedAt) {

    public static TopicMappingRuleResponse of(TopicMappingRule rule) {
        return new TopicMappingRuleResponse(rule.getId(), rule.getKeyword(), rule.getTopic().getId(),
                rule.getPriority(), new AdminRef(rule.getCreatedBy().getId(), rule.getCreatedBy().getNickname()),
                rule.getCreatedAt(), rule.getUpdatedAt());
    }
}
