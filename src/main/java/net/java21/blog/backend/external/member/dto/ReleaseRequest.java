package net.java21.blog.backend.external.member.dto;

/** {@code POST /me/external-blogs/{id}/release}. {@code deletePosts}는 필수(남기기 false, 삭제 true). */
public record ReleaseRequest(Boolean deletePosts) {
}
