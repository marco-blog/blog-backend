package net.java21.blog.backend.external.member.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.feed.FeedFormat;

/** {@code POST /external-blog-previews} 결과(007 contracts/api.md FeedPreview). */
public record FeedPreviewResponse(String feedUrl, String siteUrl, String title, FeedFormat format,
        List<RecentPost> recentPosts, Registered registered) {

    public record RecentPost(String title, String link, Instant publishedAt) {
    }

    /** 같은 피드의 거절·해제가 아닌 등록. {@code claimable}은 BLOCKED가 아니면 true, {@code mine}은 요청한 회원이 관리 회원인지. */
    public record Registered(long externalBlogId, ExternalBlogStatus status, boolean claimable, boolean mine) {
    }

    public FeedPreviewResponse withRegistered(Registered value) {
        return new FeedPreviewResponse(feedUrl, siteUrl, title, format, recentPosts, value);
    }
}
