package net.java21.blog.backend.releasenote.dto;

/** {@code POST /me/release-notes/seen}. */
public record SeenRequest(String version) {
}
