package net.java21.blog.backend.legal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 약관·개인정보처리방침 설정(FR-081, FR-155).
 *
 * @param termsVersion 현재 약관·개인정보처리방침 버전(4개 언어 공통, 필수). 가입 요청의 {@code termsVersion}과 같아야 한다.
 */
@ConfigurationProperties("blog.legal")
public record LegalProperties(String termsVersion) {

    public LegalProperties {
        if (termsVersion == null || termsVersion.isBlank()) {
            throw new IllegalArgumentException("blog.legal.terms-version is required");
        }
        if (termsVersion.length() > 20) {
            throw new IllegalArgumentException("blog.legal.terms-version must be at most 20 characters");
        }
    }
}
