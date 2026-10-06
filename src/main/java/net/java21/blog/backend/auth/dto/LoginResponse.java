package net.java21.blog.backend.auth.dto;

import java.util.List;

import net.java21.blog.backend.blog.dto.BlogLink;

/** {@code POST /auth/login} 응답. {@code blogs}는 삭제하지 않은 내 블로그, 만든 순. */
public record LoginResponse(long userId, String nickname, String role, List<BlogLink> blogs) {
}
