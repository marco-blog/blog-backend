package net.java21.blog.backend.admin.topic.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 관리자 주제 트리 한 칸(003 contracts/api.md {@code AdminTopicNode}): {@code TopicNode}에 숨김·고정·최근 글 수를 더한다.
 *
 * @param effectiveHidden 자신 또는 대분류가 운영자 숨김
 * @param recentPostCount 최근 30일 포털 노출 글 수(대분류는 숨기지 않은 소분류의 합, 최대 5분 지연)
 */
public record AdminTopicNode(Long id, String slug, Long parentId, Map<String, String> names, String cardColor,
        boolean onTab, List<AdminTopicNode> children, boolean adminHidden, boolean effectiveHidden,
        boolean pinnedOnTab, long recentPostCount, Instant createdAt, Instant updatedAt) {
}
