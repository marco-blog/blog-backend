package net.java21.blog.backend.manage.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import net.java21.blog.backend.post.domain.PostVisibility;

/**
 * {@code POST /blogs/{handle}/manage/posts/bulk}(contracts/api.md 블로그 관리 절). 글은 한 번에 최대 {@value #MAX_POSTS}개이며
 * 모두 그 블로그의 글이어야 한다.
 *
 * @param visibility {@code CHANGE_VISIBILITY}일 때 바꿀 공개 범위
 * @param categoryId {@code MOVE_CATEGORY}일 때 옮길 카테고리(같은 블로그의 것, null이면 미분류)
 */
public record BulkPostRequest(
        @NotEmpty @Size(max = MAX_POSTS) List<@NotNull Long> postIds,
        @NotNull BulkAction action,
        PostVisibility visibility,
        Long categoryId) {

    public static final int MAX_POSTS = 100;
}
