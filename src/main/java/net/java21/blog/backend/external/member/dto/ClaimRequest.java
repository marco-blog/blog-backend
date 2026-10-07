package net.java21.blog.backend.external.member.dto;

/** {@code POST /external-blogs/{id}/claim}. */
public record ClaimRequest(Long verificationId) {
}
