package net.java21.blog.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import net.java21.blog.backend.auth.validation.StrongPassword;

/** {@code POST /auth/password-reset/confirm}(FR-133). 새 비밀번호는 가입과 같은 규칙. */
public record PasswordResetConfirmRequest(@NotBlank @Size(max = 100) String token,
        @NotNull @StrongPassword String newPassword) {

    @Override
    public String toString() {
        return "PasswordResetConfirmRequest[****]";
    }
}
