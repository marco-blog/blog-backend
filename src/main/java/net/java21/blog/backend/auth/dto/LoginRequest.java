package net.java21.blog.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /auth/login}. 형식이 틀린 이메일도 규칙 검사 없이 401로 같게 응답한다. {@code captchaToken}은 005 로그인 반복 실패 뒤에만
 * 필요하다(FR-141).
 */
public record LoginRequest(@NotBlank String email, @NotBlank String password, String captchaToken) {

    /** 001~004 호출부용(CAPTCHA 없음). */
    public LoginRequest(String email, String password) {
        this(email, password, null);
    }

    @Override
    public String toString() {
        return "LoginRequest[****]";
    }
}
