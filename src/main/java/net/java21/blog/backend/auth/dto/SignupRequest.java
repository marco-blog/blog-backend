package net.java21.blog.backend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import net.java21.blog.backend.auth.validation.StrongPassword;

/**
 * {@code POST /auth/signup}(FR-001~003, FR-081). 동의 세 항목이 모두 true인지, 약관 버전·locale·timeZone은 서비스가 확인한다.
 *
 * @param locale   가입 화면의 현재 언어(ko·en·ja·zh-CN), 생략 가능
 * @param timeZone 브라우저 시간대(IANA ID), 생략하면 Asia/Seoul
 */
public record SignupRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotNull @StrongPassword String password,
        @NotBlank @Size(max = 30) String nickname,
        @NotBlank String handle,
        @NotNull Boolean agreeTerms,
        @NotNull Boolean agreePrivacy,
        @NotNull Boolean over14,
        @NotBlank @Size(max = 20) String termsVersion,
        String locale,
        String timeZone) {

    @Override
    public String toString() {
        return "SignupRequest[handle=" + handle + ", termsVersion=" + termsVersion + "]";
    }
}
