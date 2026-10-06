package net.java21.blog.backend.portal.dto;

import java.time.Instant;

import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.portal.repository.PortalCardRow;

/** 포털 카드(003 contracts/api.md {@code PortalCard}, FR-085). 주제 이름은 front가 {@code GET /topics} 트리로 찾는다. */
public record PortalCardResponse(Long id, String title, String summary, String thumbnailUrl, Long topicId,
        BlogRef blog, AuthorRef author, Instant publishedAt, int likeCount, int commentCount) {

    public record BlogRef(String handle, String title) {
    }

    public record AuthorRef(String nickname, String profileImageUrl) {
    }

    public static PortalCardResponse from(PortalCardRow row) {
        return new PortalCardResponse(row.id(), row.title(), row.summary(), row.thumbnailUrl(), row.topicId(),
                new BlogRef(row.blogHandle(), row.blogTitle()),
                new AuthorRef(row.authorNickname(), Media.urlOf(row.authorProfileMediaKey())), row.publishedAt(),
                row.likeCount(), row.commentCount());
    }
}
