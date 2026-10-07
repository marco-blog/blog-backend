package net.java21.blog.backend.block.dto;

import java.time.Instant;

/** 차단한 회원 한 줄(004 contracts/api.md 회원 차단 절): {@code { user: { userId, nickname, profileImageUrl }, blockedAt }}. */
public record BlockedUserResponse(BlockedUser user, Instant blockedAt) {

    public record BlockedUser(Long userId, String nickname, String profileImageUrl) {
    }
}
