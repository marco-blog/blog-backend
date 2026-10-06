package net.java21.blog.backend.topic.repository;

import java.time.Instant;

import net.java21.blog.backend.topic.domain.TopicNames;

/**
 * 주제 한 줄(DTO projection). {@code parentHidden}은 대분류의 운영자 숨김 값(대분류면 false).
 */
public record TopicRow(Long id, Long parentId, String slug, String nameKo, String nameEn, String nameJa,
        String nameZhCn, int sortOrder, boolean adminHidden, boolean parentHidden, boolean pinnedOnTab,
        String cardColor, Instant createdAt, Instant updatedAt) {

    public boolean isMajor() {
        return parentId == null;
    }

    /** 자신 또는 대분류가 운영자 숨김(FR-079). */
    public boolean effectiveHidden() {
        return adminHidden || parentHidden;
    }

    public TopicNames names() {
        return new TopicNames(nameKo, nameEn, nameJa, nameZhCn);
    }
}
