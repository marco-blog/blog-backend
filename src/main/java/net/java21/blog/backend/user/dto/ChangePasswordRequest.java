package net.java21.blog.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import net.java21.blog.backend.auth.validation.StrongPassword;

/** {@code PUT /me/password}(FR-082). 새 비밀번호는 가입과 같은 규칙(어기면 {@code PASSWORD_WEAK}). */
public record ChangePasswordRequest(@NotBlank String currentPassword, @NotNull @StrongPassword String newPassword) {

    @Override
    public String toString() {
        return "ChangePasswordRequest[****]";
    }
}
