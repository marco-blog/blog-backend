package net.java21.blog.backend.post.dto;

import java.time.Instant;
import java.util.List;

/** 작성 화면 불러오기({@code GET /posts/{id}/draft}): {@code DraftWrite + { savedAt }}. */
public record DraftResponse(String title, String contentMarkdown, Long categoryId, List<String> tags, Instant savedAt) {
}
