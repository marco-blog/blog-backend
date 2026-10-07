package net.java21.blog.backend.user.dto;

import java.util.List;

import net.java21.blog.backend.blog.dto.BlogLink;

/**
 * {@code GET /me}. {@code profileImageUrl}은 {@code /media/{key}} 또는 null, {@code unseenReleaseNote}는 릴리스 노트 배너 조건(003 FR-163)을 만족할 때만 값이 있다.
 *
 * @param blogs                   삭제하지 않은 내 블로그, 만든 순
 * @param unreadNotificationCount 안 읽은 알림 수(002 FR-033, 상단 배지)
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
        UnseenReleaseNote unseenReleaseNote,
        long unreadNotificationCount) {

    /** 003 FR-163 배너용 {@code { version, title }}. */
    public record UnseenReleaseNote(String version, String title) {
    }
}
