package net.java21.blog.backend.admin;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 관리자 설정(001·006 contracts/api.md "프로퍼티").
 *
 * @param bootstrapSuperAdminEmail (선택) 기동 시 SUPER_ADMIN이 한 명도 없으면 이 이메일의 회원을 SUPER_ADMIN으로 지정한다
 *                                 (006 FR-105, 첫 최고 관리자 지정의 유일한 경로). 값은 환경 변수
 *                                 {@code BLOG_ADMIN_BOOTSTRAP_SUPER_ADMIN_EMAIL}로 주며 저장소에 커밋하지 않는다
 * @param dashboardCacheTtl        콘솔 대시보드 캐시 수명(006 FR-103 "최대 5분 지연"). 0이면 캐시하지 않는다(E2E). 음수면 기동 실패
 * @param auditRetention           작업 기록 보관 기간(006 FR-106 "1년간 보관"). 지난 행은 정리 작업이 지운다. 30일 미만이면 기동 실패
 */
@ConfigurationProperties("blog.admin")
public record AdminProperties(
        String bootstrapSuperAdminEmail,
        @DefaultValue("5m") Duration dashboardCacheTtl,
        @DefaultValue("365d") Duration auditRetention) {

    /** 작업 기록 보관 기간의 하한(실수로 기록을 지우지 않게, 006 research A7). */
    public static final Duration MIN_AUDIT_RETENTION = Duration.ofDays(30);

    @ConstructorBinding
    public AdminProperties {
        if (dashboardCacheTtl == null) {
            dashboardCacheTtl = Duration.ofMinutes(5);
        }
        if (auditRetention == null) {
            auditRetention = Duration.ofDays(365);
        }
        if (dashboardCacheTtl.isNegative()) {
            throw new IllegalArgumentException("blog.admin.dashboard-cache-ttl must not be negative");
        }
        if (auditRetention.compareTo(MIN_AUDIT_RETENTION) < 0) {
            throw new IllegalArgumentException("blog.admin.audit-retention must be at least 30 days");
        }
    }

    /** 첫 최고 관리자 이메일만 정하고 나머지는 기본값(테스트·첫 지정 확인용). */
    public AdminProperties(String bootstrapSuperAdminEmail) {
        this(bootstrapSuperAdminEmail, null, null);
    }

    public boolean hasBootstrapEmail() {
        return bootstrapSuperAdminEmail != null && !bootstrapSuperAdminEmail.isBlank();
    }
}
