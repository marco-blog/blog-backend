package net.java21.blog.backend.post.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * 작성 화면의 내용(contracts/api.md {@code DraftWrite}). 임시저장은 빈 제목을 허용한다(FR-016).
 * {@code categoryId}·{@code tags}는 작성 중 사본에만 저장하고 검증·반영은 발행 때 한다(T169).
 * 003: {@code topicId}(소분류, null = 선택 안 함)도 같은 방식으로 사본에만 저장하고 발행 때 검증한다(003 research P9).
 */
public record DraftWriteRequest(
        @Size(max = 200) String title,
        @Size(max = 200_000) String contentMarkdown,
        Long categoryId,
        List<String> tags,
        Long topicId) {
}
