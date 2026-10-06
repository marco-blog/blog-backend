package net.java21.blog.backend.blog.dto;

import java.util.List;

import net.java21.blog.backend.blog.domain.Blog;

/**
 * {@code GET /blogs/{handle}} 응답. 대표·프로필 이미지 주소는 미디어(US4) 전까지 null, 카테고리는 US2 전까지 빈 배열.
 */
public record BlogResponse(
        String handle,
        String title,
        String description,
        String coverImageUrl,
        boolean commentEnabled,
        Owner owner,
        List<CategoryNode> categories) {

    public record Owner(String nickname, String profileImageUrl, String bio) {
    }

    /** 블로그와 주인(이미 읽어 둔 연관)으로 만든다. */
    public static BlogResponse of(Blog blog) {
        var user = blog.getUser();
        return new BlogResponse(blog.getHandle(), blog.getTitle(), blog.getDescription(), null,
                blog.isCommentEnabled(), new Owner(user.getNickname(), null, user.getBio()), List.of());
    }
}
