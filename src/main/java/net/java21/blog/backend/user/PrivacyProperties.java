package net.java21.blog.backend.user;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 개인정보 보관 기간(contracts/api.md "프로퍼티", FR-138·139). 파기 주기는 {@code blog.jobs.privacy-purge-cron}.
 *
 * @param withdrawnRetention    탈퇴 후 개인정보 파기까지(분쟁 대응 보존 기간, 기본 30일)
 * @param loginHistoryRetention 로그인 기록 보관 기간(기본 90일)
 */
@ConfigurationProperties("blog.privacy")
public record PrivacyProperties(
        @DefaultValue("30d") Duration withdrawnRetention,
        @DefaultValue("90d") Duration loginHistoryRetention) {

    public PrivacyProperties {
        if (withdrawnRetention.isNegative() || withdrawnRetention.isZero()) {
            throw new IllegalArgumentException("blog.privacy.withdrawn-retention must be positive");
        }
        if (loginHistoryRetention.isNegative() || loginHistoryRetention.isZero()) {
            throw new IllegalArgumentException("blog.privacy.login-history-retention must be positive");
        }
    }
}
