package net.java21.blog.backend.external.member.dto;

/** {@code PUT /me/external-blogs/{id}/posts/{postId}/topic}. */
public record PostTopicRequest(Long topicId) {
}
