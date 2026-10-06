package net.java21.blog.backend.post.dto;

import java.time.Instant;
import java.util.List;

/**
 * 작성 화면 불러오기({@code GET /posts/{id}/draft}): {@code DraftWrite + { savedAt }}.
 * {@code topicId}는 작성 중 사본의 주제, 사본이 없으면 발행본의 주제(003 contracts/api.md).
 */
public record DraftResponse(String title, String contentMarkdown, Long categoryId, List<String> tags, Long topicId,
        Instant savedAt) {
}
