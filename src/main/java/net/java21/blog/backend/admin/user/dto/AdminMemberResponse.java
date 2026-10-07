package net.java21.blog.backend.admin.user.dto;

import java.time.Instant;

import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;

/** 관리자 목록·권한 변경 응답 {@code AdminMember}(006 contracts/api.md). */
public record AdminMemberResponse(long userId, String nickname, UserRole role, UserStatus status, Instant createdAt) {
}
