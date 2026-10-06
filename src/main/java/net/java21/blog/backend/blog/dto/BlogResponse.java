package net.java21.blog.backend.blog.dto;

import java.util.List;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.category.dto.CategoryNode;

/**
 * {@code GET /blogs/{handle}} 응답. 대표·프로필 이미지 주소는 {@code /media/{key}} 또는 null. 카테고리는 트리({@link CategoryNode}).
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

    /** 블로그와 주인(이미 읽어 둔 연관)으로 만든다. 카테고리 없음(새 블로그). 이미지가 있으면 그 키를 읽는다. */
    public static BlogResponse of(Blog blog) {
        return of(blog, List.of());
    }

    /** 블로그와 주인(이미 읽어 둔 연관), 카테고리 트리로 만든다. */
    public static BlogResponse of(Blog blog, List<CategoryNode> categories) {
        var user = blog.getUser();
        return new BlogResponse(blog.getHandle(), blog.getTitle(), blog.getDescription(), blog.coverImageUrl(),
                blog.isCommentEnabled(), new Owner(user.getNickname(), user.profileImageUrl(), user.getBio()),
                categories);
    }
}
