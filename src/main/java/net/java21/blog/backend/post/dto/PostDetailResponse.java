package net.java21.blog.backend.post.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;

/**
 * 글 상세(contracts/api.md {@code PostDetail}). {@code contentMarkdown}은 주인에게만 주고 그 외에는 null.
 * {@code commentEnabled}는 글별 설정이다(블로그 설정이 꺼져 있으면 댓글 API가 막는다, FR-029·107).
 * 카테고리는 미분류면 null, 태그는 이름순. 프로필 이미지는 US4 전까지 null.
 */
public record PostDetailResponse(
        Long id,
        String blogHandle,
        String title,
        String contentHtml,
        String contentMarkdown,
        String summary,
        String thumbnailUrl,
        CategoryRef category,
        List<String> tags,
        PostVisibility visibility,
        PostStatus status,
        int viewCount,
        int commentCount,
        boolean commentEnabled,
        Author author,
        PostLink prev,
        PostLink next,
        Instant publishedAt,
        Instant updatedAt) {

    public record Author(String nickname, String profileImageUrl) {
    }

    /** 이미 읽은 글(블로그·주인·카테고리 포함)과 태그 이름으로 만든다. */
    public static PostDetailResponse of(Post post, boolean owner, PostLink prev, PostLink next, List<String> tags) {
        var user = post.getBlog().getUser();
        return new PostDetailResponse(post.getId(), post.getBlog().getHandle(), post.getTitle(), post.getContentHtml(),
                owner ? post.getContentMarkdown() : null, post.getSummary(), post.getThumbnailUrl(), CategoryRef.of(post.getCategory()),
                tags == null ? List.of() : tags,
                post.getVisibility(), post.getStatus(), post.getViewCount(), post.getCommentCount(),
                post.isCommentEnabled(), new Author(user.getNickname(), null), prev, next,
                post.getPublishedAt(), post.getUpdatedAt());
    }
}
