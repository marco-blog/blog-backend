package net.java21.blog.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /auth/password-reset/request}(FR-133). */
public record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {

    @Override
    public String toString() {
        return "PasswordResetRequest[****]";
    }
}
