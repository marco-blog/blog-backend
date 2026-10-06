package net.java21.blog.backend.portal.dto;

import java.time.Instant;

import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.portal.repository.NewBlogRow;

/** 새로 시작한 블로그(003 contracts/api.md {@code PortalHome.newBlogs}, FR-087). */
public record NewBlogResponse(String handle, String title, String description, String coverImageUrl, Owner owner,
        Instant firstPublishedAt) {

    public record Owner(String nickname, String profileImageUrl) {
    }

    public static NewBlogResponse from(NewBlogRow row) {
        return new NewBlogResponse(row.handle(), row.title(), row.description(), Media.urlOf(row.coverMediaKey()),
                new Owner(row.ownerNickname(), Media.urlOf(row.ownerProfileMediaKey())), row.firstPublishedAt());
    }
}
