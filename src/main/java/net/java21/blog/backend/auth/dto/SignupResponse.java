package net.java21.blog.backend.auth.dto;

/** {@code POST /auth/signup} 201 응답. */
public record SignupResponse(long userId, String handle) {
}
