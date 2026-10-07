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
 * 카테고리는 미분류면 null, 태그는 이름순. 작성자 프로필 이미지는 {@code /media/{key}} 또는 null.
 * 002: {@code likeCount}(좋아요 수), {@code likedByMe}(로그인 회원이 눌렀으면 true, 아니면 false, 비로그인이면 null).
 * 003: {@code topicId}(글의 주제 소분류, 없으면 null). 주제 이름은 front가 {@code GET /topics} 트리로 찾는다.
 * 004: {@code notice}(공지), {@code locked}(열지 않은 보호 글이면 true — 본문·요약·대표 이미지·카테고리·주제는 null, 태그는 빈 배열),
 * {@code scheduledAt}(예약 시각, 주인에게만).
 * 005: {@code hidden}(관리자가 숨긴 글, 주인에게만 true일 수 있음 — 다른 사람에게는 상세가 404),
 * {@code trackbackUrl}(본문 노출 가능 + 블로그 트랙백 받기일 때만, 아니면 null), {@code trackbackCount}(목록에 보이는 트랙백 수).
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
        Instant updatedAt,
        int likeCount,
        Boolean likedByMe,
        Long topicId,
        boolean notice,
        boolean locked,
        Instant scheduledAt,
        boolean hidden,
        String trackbackUrl,
        long trackbackCount) {

    /** 트랙백 정보가 없는 상세(005 US1까지의 호출부). */
    public PostDetailResponse(Long id, String blogHandle, String title, String contentHtml, String contentMarkdown,
            String summary, String thumbnailUrl, CategoryRef category, List<String> tags, PostVisibility visibility,
            PostStatus status, int viewCount, int commentCount, boolean commentEnabled, Author author, PostLink prev,
            PostLink next, Instant publishedAt, Instant updatedAt, int likeCount, Boolean likedByMe, Long topicId,
            boolean notice, boolean locked, Instant scheduledAt, boolean hidden) {
        this(id, blogHandle, title, contentHtml, contentMarkdown, summary, thumbnailUrl, category, tags, visibility,
                status, viewCount, commentCount, commentEnabled, author, prev, next, publishedAt, updatedAt, likeCount,
                likedByMe, topicId, notice, locked, scheduledAt, hidden, null, 0);
    }

    /** 숨기지 않은 글(001~004 호출부). */
    public PostDetailResponse(Long id, String blogHandle, String title, String contentHtml, String contentMarkdown,
            String summary, String thumbnailUrl, CategoryRef category, List<String> tags, PostVisibility visibility,
            PostStatus status, int viewCount, int commentCount, boolean commentEnabled, Author author, PostLink prev,
            PostLink next, Instant publishedAt, Instant updatedAt, int likeCount, Boolean likedByMe, Long topicId,
            boolean notice, boolean locked, Instant scheduledAt) {
        this(id, blogHandle, title, contentHtml, contentMarkdown, summary, thumbnailUrl, category, tags, visibility,
                status, viewCount, commentCount, commentEnabled, author, prev, next, publishedAt, updatedAt, likeCount,
                likedByMe, topicId, notice, locked, scheduledAt, false);
    }

    public record Author(String nickname, String profileImageUrl) {
    }

    /**
     * 이미 읽은 글(블로그·주인·카테고리 포함)과 태그 이름, 좋아요 여부(비로그인 null)로 만든다. {@code locked}면 제목·작성자·블로그·
     * 발행 시각·공개 범위만 남긴다(004 research B4).
     */
    public static PostDetailResponse of(Post post, boolean owner, PostLink prev, PostLink next, List<String> tags,
            Boolean likedByMe, boolean locked) {
        return of(post, owner, prev, next, tags, likedByMe, locked, null, 0);
    }

    /** 트랙백 주소(받지 않으면 null)와 보이는 트랙백 수를 더한 상세(005). */
    public static PostDetailResponse of(Post post, boolean owner, PostLink prev, PostLink next, List<String> tags,
            Boolean likedByMe, boolean locked, String trackbackUrl, long trackbackCount) {
        var user = post.getBlog().getUser();
        return new PostDetailResponse(post.getId(), post.getBlog().getHandle(), post.getTitle(),
                locked ? null : post.getContentHtml(),
                owner ? post.getContentMarkdown() : null,
                locked ? null : post.getSummary(),
                locked ? null : post.getThumbnailUrl(),
                locked ? null : CategoryRef.of(post.getCategory()),
                locked || tags == null ? List.of() : tags,
                post.getVisibility(), post.getStatus(), post.getViewCount(), post.getCommentCount(),
                owner ? post.isCommentEnabled() : post.isCommentEnabled() && post.getBlog().isCommentEnabled(),
                new Author(user.getNickname(), user.profileImageUrl()), prev, next,
                post.getPublishedAt(), post.getUpdatedAt(), post.getLikeCount(), likedByMe,
                locked ? null : post.getTopicId(), post.isNotice(), locked, owner ? post.getScheduledAt() : null,
                post.isHidden(), trackbackUrl, trackbackCount);
    }

    /** 잠기지 않은 상세(001~003 호출부). */
    public static PostDetailResponse of(Post post, boolean owner, PostLink prev, PostLink next, List<String> tags,
            Boolean likedByMe) {
        return of(post, owner, prev, next, tags, likedByMe, false);
    }
}
