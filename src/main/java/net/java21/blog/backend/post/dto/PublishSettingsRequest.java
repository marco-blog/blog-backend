package net.java21.blog.backend.post.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import net.java21.blog.backend.post.domain.PostVisibility;

/**
 * 발행 설정(contracts/api.md {@code PublishSettings}, FR-107).
 * {@code thumbnailMediaKey}는 본문 이미지 중 하나(생략하면 본문 첫 이미지), {@code commentEnabled}를 생략하면 true.
 * {@code categoryId}·{@code tags}·{@code topicId}가 null이면 작성 중 사본의 값을 쓴다({@code PostPublishService}).
 * {@code notice}(004 FR-059)는 true면 공지, false면 해제, null이면 지금 값 유지(새 글은 false).
 * {@code password}(004 FR-062)는 PROTECTED일 때 보호 글 비밀번호(4~64자, 이미 보호 글이면 생략 가능),
 * {@code scheduledAt}(004 FR-064)이 미래면 예약 발행, 없거나 지금 이하면 즉시 발행. 검증은 {@code PostPublishService}가 한다.
 */
public record PublishSettingsRequest(
        @NotNull PostVisibility visibility,
        @Pattern(regexp = "[A-Za-z0-9]{22}") String thumbnailMediaKey,
        Boolean commentEnabled,
        Long categoryId,
        List<String> tags,
        Long topicId,
        Boolean notice,
        String password,
        Instant scheduledAt) {

    /** 001~003 호출부용(공지는 그대로). */
    public PublishSettingsRequest(PostVisibility visibility, String thumbnailMediaKey, Boolean commentEnabled,
            Long categoryId, List<String> tags, Long topicId) {
        this(visibility, thumbnailMediaKey, commentEnabled, categoryId, tags, topicId, null);
    }

    /** 004 US2 호출부용(보호·예약 없음). */
    public PublishSettingsRequest(PostVisibility visibility, String thumbnailMediaKey, Boolean commentEnabled,
            Long categoryId, List<String> tags, Long topicId, Boolean notice) {
        this(visibility, thumbnailMediaKey, commentEnabled, categoryId, tags, topicId, notice, null, null);
    }

    /** 로그에 비밀번호가 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "PublishSettingsRequest[visibility=" + visibility + ", categoryId=" + categoryId + ", topicId="
                + topicId + ", notice=" + notice + ", password=" + (password == null ? null : "****")
                + ", scheduledAt=" + scheduledAt + "]";
    }
}
