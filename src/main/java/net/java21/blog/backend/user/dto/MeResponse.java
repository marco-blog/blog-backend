package net.java21.blog.backend.user.dto;

import java.util.List;

import net.java21.blog.backend.blog.dto.BlogLink;

/**
 * {@code GET /me}. {@code profileImageUrl}은 {@code /media/{key}} 또는 null, {@code unseenReleaseNote}는 003 전까지 항상 null.
 *
 * @param blogs 삭제하지 않은 내 블로그, 만든 순
 */
public record MeResponse(
        long userId,
        String email,
        String nickname,
        String bio,
        String profileImageUrl,
        String role,
        String locale,
        String timeZone,
        List<BlogLink> blogs,
        UnseenReleaseNote unseenReleaseNote) {

    /** 003 FR-163 배너용 {@code { version, title }}. */
    public record UnseenReleaseNote(String version, String title) {
    }
}
