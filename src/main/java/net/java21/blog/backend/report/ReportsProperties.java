package net.java21.blog.backend.report;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 신고 설정(005 contracts/api.md "프로퍼티", {@code blog.reports.*}). 잘못된 값이면 기동하지 않는다.
 *
 * @param memberPerHour            회원 신고 1시간 한도(남용 방지, research M2)
 * @param rightsRequestPerIpPerHour 권리 침해 신고 IP당 1시간 한도
 * @param penaltyWindow            포털 감점에 넣는 처리(ACTIONED) 신고 기간(research M6)
 */
@ConfigurationProperties("blog.reports")
public record ReportsProperties(
        @DefaultValue("30") int memberPerHour,
        @DefaultValue("5") int rightsRequestPerIpPerHour,
        @DefaultValue("90d") Duration penaltyWindow) {

    public ReportsProperties {
        if (memberPerHour < 1) {
            throw new IllegalArgumentException("blog.reports.member-per-hour must be at least 1");
        }
        if (rightsRequestPerIpPerHour < 1) {
            throw new IllegalArgumentException("blog.reports.rights-request-per-ip-per-hour must be at least 1");
        }
        if (penaltyWindow == null || penaltyWindow.isNegative() || penaltyWindow.isZero()) {
            throw new IllegalArgumentException("blog.reports.penalty-window must be positive");
        }
    }

    /** 기본값(테스트용). */
    public static ReportsProperties defaults() {
        return new ReportsProperties(30, 5, Duration.ofDays(90));
    }
}
