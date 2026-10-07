package net.java21.blog.backend.report.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 권리 침해 신고 {@code { targetUrl, reason, rightsBasis, contactEmail, captchaToken }}(005 contracts/api.md). */
public record RightsRequestRequest(
        @NotBlank @Size(max = 1000) String targetUrl,
        String reason,
        @NotBlank @Size(max = 2000) String rightsBasis,
        @NotBlank @Email @Size(max = 254) String contactEmail,
        String captchaToken) {
}
