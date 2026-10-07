package net.java21.blog.backend.external.verify;

import java.time.Instant;

import net.java21.blog.backend.external.domain.ExternalBlogVerification;

/** 소유 인증(007 contracts/api.md Verification). */
public record VerificationResponse(long id, String feedUrl, String code, Instant expiresAt, Instant verifiedAt,
        Long claimableExternalBlogId) {

    public static VerificationResponse of(ExternalBlogVerification v, String feedUrl, Long claimable) {
        return new VerificationResponse(v.getId(), feedUrl, v.getCode(), v.getExpiresAt(), v.getVerifiedAt(),
                claimable);
    }
}
