package net.java21.blog.backend.post.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * 작성 화면의 내용(contracts/api.md {@code DraftWrite}). 임시저장은 빈 제목을 허용한다(FR-016).
 * {@code categoryId}·{@code tags}는 작성 중 사본에만 저장하고 검증·반영은 발행 때 한다(US2).
 */
public record DraftWriteRequest(
        @Size(max = 200) String title,
        @Size(max = 200_000) String contentMarkdown,
        Long categoryId,
        List<String> tags) {
}
