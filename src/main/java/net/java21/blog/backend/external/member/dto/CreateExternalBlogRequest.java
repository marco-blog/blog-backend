package net.java21.blog.backend.external.member.dto;

/** {@code POST /me/external-blogs}. */
public record CreateExternalBlogRequest(String feedUrl, Long defaultTopicId, Long verificationId) {
}
