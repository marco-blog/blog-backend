package net.java21.blog.backend.external.dto;

import java.time.Instant;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.FetchResultCode;
import net.java21.blog.backend.external.domain.RegistrationType;
import net.java21.blog.backend.external.feed.FeedFormat;

/**
 * 회원의 외부 블로그(007 contracts/api.md MyExternalBlog). {@code postCount}는 ACTIVE 외부 글 수(해제면 남긴 글 수 — 포털에 노출
 * 중).
 */
public record MyExternalBlogResponse(long id, String title, String siteUrl, String feedUrl, FeedFormat feedFormat,
        ExternalBlogStatus status, RegistrationType registrationType, boolean ownershipVerified, long defaultTopicId,
        String rejectReason, Instant lastFetchedAt, Instant lastSuccessAt, FetchResultCode lastFetchResult,
        long postCount, Instant createdAt) {

    public static MyExternalBlogResponse of(ExternalBlog b, long postCount) {
        return new MyExternalBlogResponse(b.getId(), b.getTitle(), b.getSiteUrl(), b.getFeedUrl(), b.getFeedFormat(),
                b.getStatus(), b.getRegistrationType(), b.isOwnershipVerified(), b.getDefaultTopic().getId(),
                b.getRejectReason(), b.getLastFetchedAt(), b.getLastSuccessAt(), b.getLastFetchResult(), postCount,
                b.getCreatedAt());
    }
}
