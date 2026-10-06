package net.java21.blog.backend.security;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 인증 설정(001 contracts/api.md "프로퍼티", research R2·R12).
 *
 * @param accessTtl          접근 토큰(JWT) 수명
 * @param refreshIdleTtl     리프레시 토큰 유휴 만료(마지막 사용 후)
 * @param refreshAbsoluteTtl 리프레시 로그인 계열(family) 절대 만료(최초 로그인 후)
 * @param refreshReuseGrace  동시 리프레시 유예: 사용 처리 후 이 시간 안의 같은 토큰 재요청에는 방금 발급한 토큰을 돌려준다
 * @param jwtSecret          HS256 서명 키(UTF-8 32바이트 이상, 필수). 저장소에 커밋하지 않는다:
 *                           local은 {@code .env}의 {@code BLOG_AUTH_JWT_SECRET}, prod는 같은 이름의 환경 변수,
 *                           test는 application-test.yml의 테스트 전용 값
 * @param loginMaxFailures   연속 실패 몇 번에 잠글지
 * @param loginLockDuration  잠금 시간
 */
@ConfigurationProperties("blog.auth")
public record AuthProperties(
        @DefaultValue("30m") Duration accessTtl,
        @DefaultValue("4h") Duration refreshIdleTtl,
        @DefaultValue("7d") Duration refreshAbsoluteTtl,
        @DefaultValue("10s") Duration refreshReuseGrace,
        String jwtSecret,
        @DefaultValue("5") int loginMaxFailures,
        @DefaultValue("10m") Duration loginLockDuration) {

    /** HS256 키 최소 길이(RFC 7518 3.2: 해시 출력 크기 이상). */
    public static final int MIN_SECRET_BYTES = 32;

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalArgumentException("blog.auth.jwt-secret is required (env BLOG_AUTH_JWT_SECRET)");
        }
        if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(
                    "blog.auth.jwt-secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
        }
    }

    /** 비밀 값이 로그·오류 메시지에 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "AuthProperties[accessTtl=" + accessTtl + ", refreshIdleTtl=" + refreshIdleTtl
                + ", refreshAbsoluteTtl=" + refreshAbsoluteTtl + ", refreshReuseGrace=" + refreshReuseGrace
                + ", jwtSecret=****, loginMaxFailures=" + loginMaxFailures
                + ", loginLockDuration=" + loginLockDuration + "]";
    }
}
