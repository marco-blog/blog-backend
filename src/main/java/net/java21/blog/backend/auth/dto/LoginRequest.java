package net.java21.blog.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /auth/login}. 형식이 틀린 이메일도 규칙 검사 없이 401로 같게 응답한다. */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {

    @Override
    public String toString() {
        return "LoginRequest[****]";
    }
}
