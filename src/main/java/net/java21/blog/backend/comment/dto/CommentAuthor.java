package net.java21.blog.backend.comment.dto;

/** 댓글 작성자. 프로필 이미지는 US4 전까지 null. */
public record CommentAuthor(Long userId, String nickname, String profileImageUrl) {
}
