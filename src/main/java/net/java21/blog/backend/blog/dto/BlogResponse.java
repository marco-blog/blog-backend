package net.java21.blog.backend.blog.dto;

import java.util.List;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.FeedContentMode;
import net.java21.blog.backend.category.dto.CategoryNode;

/**
 * {@code GET /blogs/{handle}} 응답. 대표·프로필 이미지 주소는 {@code /media/{key}} 또는 null. 카테고리는 트리({@link CategoryNode}).
 * 002: 구독자 수, {@code subscribedByMe}(로그인 회원이 구독 중이면 true, 아니면 false, 비로그인이면 null), 피드 설정.
 * 003: {@code portalEnabled}("포털에 내 글 노출"), {@code defaultTopicId}(블로그 기본 주제, 없으면 null).
 * 004: {@code guestbookEnabled}(방명록 사용, FR-058), {@code guestWriteEnabled}(비회원 댓글·방명록 허용, FR-066).
 * 005: {@code trackbackEnabled}(트랙백 받기, FR-053).
 */
public record BlogResponse(
        String handle,
        String title,
        String description,
        String coverImageUrl,
        boolean commentEnabled,
        Owner owner,
        List<CategoryNode> categories,
        int subscriberCount,
        Boolean subscribedByMe,
        int feedItemCount,
        FeedContentMode feedContentMode,
        boolean portalEnabled,
        Long defaultTopicId,
        boolean guestbookEnabled,
        boolean guestWriteEnabled,
        boolean trackbackEnabled) {

    public record Owner(String nickname, String profileImageUrl, String bio) {
    }

    /**
     * 주인이 받는 응답(만들기·수정): 카테고리 없음(새 블로그). 자기 블로그는 구독할 수 없으므로 {@code subscribedByMe}는 false.
     * 이미지가 있으면 그 키를 읽는다.
     */
    public static BlogResponse of(Blog blog) {
        return of(blog, List.of(), false);
    }

    /** 블로그와 주인(이미 읽어 둔 연관), 카테고리 트리, 요청한 회원의 구독 여부(비로그인 null)로 만든다. */
    public static BlogResponse of(Blog blog, List<CategoryNode> categories, Boolean subscribedByMe) {
        var user = blog.getUser();
        return new BlogResponse(blog.getHandle(), blog.getTitle(), blog.getDescription(), blog.coverImageUrl(),
                blog.isCommentEnabled(), new Owner(user.getNickname(), user.profileImageUrl(), user.getBio()),
                categories, blog.getSubscriberCount(), subscribedByMe, blog.getFeedItemCount(),
                blog.getFeedContentMode(), blog.isPortalEnabled(), blog.getDefaultTopicId(), blog.isGuestbookEnabled(),
                blog.isGuestWriteEnabled(), blog.isTrackbackEnabled());
    }
}
