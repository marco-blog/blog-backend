package net.java21.blog.backend.admin.external.dto;

/** {@code POST}·{@code PATCH /admin/topic-mapping-rules}. 수정은 보낸 값만 바꾼다. */
public record MappingRuleRequest(String keyword, Long topicId, Integer priority) {
}
