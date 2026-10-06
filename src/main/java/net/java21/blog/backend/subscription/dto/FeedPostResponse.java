package net.java21.blog.backend.subscription.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.CategoryRef;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.subscription.repository.FeedPostRow;

/**
 * 구독 피드 한 줄(002 contracts/api.md {@code FeedPost}): PostSummary(001) + {@code blog}·{@code author}.
 * {@code hasDraft}는 항상 false, 본문 노출 가능이 아닌 글(004 보호 글)은 {@code summary}·{@code thumbnailUrl}이 null.
 */
public record FeedPostResponse(
        Long id,
        String title,
        String summary,
        String thumbnailUrl,
        CategoryRef category,
        List<String> tags,
        int viewCount,
        int commentCount,
        PostVisibility visibility,
        PostStatus status,
        Instant publishedAt,
        Instant updatedAt,
        boolean hasDraft,
        BlogRef blog,
        Author author) {

    public record BlogRef(String handle, String title) {
    }

    public record Author(String nickname, String profileImageUrl) {
    }

    public static FeedPostResponse of(FeedPostRow row, List<String> tags) {
        boolean bodyVisible = PostExposure.isBodyVisibleListed(row.visibility());
        return new FeedPostResponse(row.id(), row.title(), bodyVisible ? row.summary() : null,
                bodyVisible ? row.thumbnailUrl() : null, CategoryRef.of(row.categoryId(), row.categoryName()),
                tags == null ? List.of() : tags, row.viewCount(), row.commentCount(), row.visibility(), row.status(),
                row.publishedAt(), row.updatedAt(), false, new BlogRef(row.blogHandle(), row.blogTitle()),
                new Author(row.authorNickname(), Media.urlOf(row.authorProfileMediaKey())));
    }
}
