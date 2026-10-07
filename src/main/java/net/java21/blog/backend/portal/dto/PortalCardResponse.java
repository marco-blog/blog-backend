package net.java21.blog.backend.portal.dto;

import java.time.Instant;

import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.portal.repository.PortalCardRow;

/**
 * 포털 카드(003 contracts/api.md {@code PortalCard}, FR-085). 주제 이름은 front가 {@code GET /topics} 트리로 찾는다.
 * 007이 출처를 더했다(contracts/api.md "003 API 확장"): 내부 카드는 {@code source=INTERNAL}·{@code visitUrl}·{@code externalBlog}
 * null로 003과 같고, 외부 카드는 {@code blog.handle}·{@code author} null, 좋아요·댓글 0, {@code visitUrl}로 원문에 간다. id는 출처마다
 * 따로라 front 키는 {@code ${source}-${id}}.
 */
public record PortalCardResponse(Long id, String source, String title, String summary, String thumbnailUrl,
        Long topicId, BlogRef blog, AuthorRef author, ExternalBlogRef externalBlog, String visitUrl,
        Instant publishedAt, int likeCount, int commentCount) {

    public static final String INTERNAL = "INTERNAL";
    public static final String EXTERNAL = "EXTERNAL";

    /** 내부 글 카드(003). */
    public PortalCardResponse(Long id, String title, String summary, String thumbnailUrl, Long topicId, BlogRef blog,
            AuthorRef author, Instant publishedAt, int likeCount, int commentCount) {
        this(id, INTERNAL, title, summary, thumbnailUrl, topicId, blog, author, null, null, publishedAt, likeCount,
                commentCount);
    }

    /** 외부 블로그(007): 이름이 없으면 호스트, 호스트는 사이트 주소(없으면 피드 주소)의 것. */
    public record ExternalBlogRef(Long id, String title, String siteHost) {
    }

    public record BlogRef(String handle, String title) {
    }

    public record AuthorRef(String nickname, String profileImageUrl) {
    }

    /** 외부 글 카드(007). */
    public static PortalCardResponse external(Long id, String title, String summary, String thumbnailUrl,
            Long topicId, ExternalBlogRef externalBlog, Instant publishedAt) {
        return new PortalCardResponse(id, EXTERNAL, title, summary, thumbnailUrl, topicId,
                new BlogRef(null, externalBlog.title()), null, externalBlog, visitUrl(id), publishedAt, 0, 0);
    }

    /** 원문 이동 주소(클릭을 센 뒤 302, research E14). */
    public static String visitUrl(Long externalPostId) {
        return "/api/v1/external-posts/" + externalPostId + "/visit";
    }

    public static PortalCardResponse from(PortalCardRow row) {
        return new PortalCardResponse(row.id(), row.title(), row.summary(), row.thumbnailUrl(), row.topicId(),
                new BlogRef(row.blogHandle(), row.blogTitle()),
                new AuthorRef(row.authorNickname(), Media.urlOf(row.authorProfileMediaKey())), row.publishedAt(),
                row.likeCount(), row.commentCount());
    }
}
