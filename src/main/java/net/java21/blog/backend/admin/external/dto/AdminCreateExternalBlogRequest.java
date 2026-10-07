package net.java21.blog.backend.admin.external.dto;

/** {@code POST /admin/external-blogs}. */
public record AdminCreateExternalBlogRequest(String feedUrl, Long defaultTopicId, String registrationBasis) {
}
