package net.java21.blog.backend.user.dto;

import jakarta.validation.constraints.NotBlank;

/** {@code DELETE /me}(FR-009): 되돌릴 수 없으므로 비밀번호를 다시 확인한다. */
public record WithdrawRequest(@NotBlank String password) {

    @Override
    public String toString() {
        return "WithdrawRequest[****]";
    }
}
