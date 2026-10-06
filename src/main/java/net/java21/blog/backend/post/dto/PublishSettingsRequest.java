package net.java21.blog.backend.post.dto;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import net.java21.blog.backend.post.domain.PostVisibility;

/**
 * 발행 설정(contracts/api.md {@code PublishSettings}, FR-107).
 * {@code thumbnailMediaKey}는 본문 이미지 중 하나(생략하면 본문 첫 이미지), {@code commentEnabled}를 생략하면 true.
 * {@code categoryId}·{@code tags}가 null이면 작성 중 사본의 값을 쓴다({@code PostPublishService}).
 */
public record PublishSettingsRequest(
        @NotNull PostVisibility visibility,
        @Pattern(regexp = "[A-Za-z0-9]{22}") String thumbnailMediaKey,
        Boolean commentEnabled,
        Long categoryId,
        List<String> tags) {
}
