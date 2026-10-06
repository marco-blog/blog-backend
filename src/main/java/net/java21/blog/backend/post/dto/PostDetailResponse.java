package net.java21.blog.backend.post.dto;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;

/**
 * 글 상세(contracts/api.md {@code PostDetail}). {@code contentMarkdown}은 주인에게만 주고 그 외에는 null.
 * {@code commentEnabled}는 주인 외에게는 블로그 설정을 반영한 값(블로그와 글 모두 허용일 때만 true, FR-029·107)이고,
 * 주인에게는 작성 화면이 그대로 다시 저장하는 글별 설정 값이다(댓글 API는 두 설정을 모두 검사한다).
 * 카테고리·태그는 US2, 프로필 이미지는 US4 전까지 각각 null·빈 배열·null.
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

    /** 이미 읽은 글(블로그·주인 포함)로 만든다. */
    public static PostDetailResponse of(Post post, boolean owner, PostLink prev, PostLink next) {
        var user = post.getBlog().getUser();
        return new PostDetailResponse(post.getId(), post.getBlog().getHandle(), post.getTitle(), post.getContentHtml(),
                owner ? post.getContentMarkdown() : null, post.getSummary(), post.getThumbnailUrl(), null, List.of(),
                post.getVisibility(), post.getStatus(), post.getViewCount(), post.getCommentCount(),
                owner ? post.isCommentEnabled() : post.isCommentEnabled() && post.getBlog().isCommentEnabled(),
                new Author(user.getNickname(), null), prev, next,
                post.getPublishedAt(), post.getUpdatedAt());
    }
}
