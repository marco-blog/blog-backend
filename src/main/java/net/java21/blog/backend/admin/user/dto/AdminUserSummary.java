package net.java21.blog.backend.admin.user.dto;

import java.time.Instant;

import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;

/** 관리자 회원 목록 한 줄(005 contracts/api.md {@code AdminUserSummary}). 이메일은 주지 않는다(006 FR-104). */
public record AdminUserSummary(Long id, String nickname, UserStatus status, UserRole role, Instant createdAt,
        long blogCount) {
}
