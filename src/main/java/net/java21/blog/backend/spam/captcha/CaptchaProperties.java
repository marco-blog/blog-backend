package net.java21.blog.backend.spam.captcha;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CAPTCHA(005 FR-141, research M9, {@code blog.captcha.*}). 운영 프로필(prod)에서 {@code test}·{@code none}이거나 Turnstile 키가 비면
 * {@link CaptchaConfig}가 기동을 멈춘다. secret은 커밋하지 않고 환경 변수({@code BLOG_CAPTCHA_SECRET_KEY})로만 준다.
 *
 * @param provider                  {@code turnstile} / {@code test} / {@code none}
 * @param siteKey                   Turnstile 사이트 키(공개 값, front 위젯용)
 * @param secretKey                 Turnstile 비밀 키(서버 검증용, 응답·로그에 넣지 않는다)
 * @param testToken                 {@code test} provider가 통과시키는 토큰
 * @param verifyTimeout             Turnstile 검증 연결·응답 시간 제한
 * @param verifyUrl                 Turnstile siteverify 주소(시험에서 바꾼다)
 * @param loginFailuresBeforeCaptcha 로그인 CAPTCHA가 필요해지는 연속 실패 수
 */
@ConfigurationProperties("blog.captcha")
public record CaptchaProperties(
        @DefaultValue("none") Provider provider,
        String siteKey,
        String secretKey,
        @DefaultValue("e2e-pass") String testToken,
        @DefaultValue("3s") Duration verifyTimeout,
        @DefaultValue("https://challenges.cloudflare.com/turnstile/v0/siteverify") String verifyUrl,
        @DefaultValue("3") int loginFailuresBeforeCaptcha) {

    /** CAPTCHA 제공자. */
    public enum Provider {
        TURNSTILE,
        TEST,
        NONE;

        /** 응답·설정 값({@code turnstile}·{@code test}·{@code none}). */
        public String value() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public CaptchaProperties {
        if (provider == null) {
            provider = Provider.NONE;
        }
        if (testToken == null || testToken.isBlank()) {
            throw new IllegalArgumentException("blog.captcha.test-token must not be blank");
        }
        if (verifyTimeout == null || verifyTimeout.isNegative() || verifyTimeout.isZero()) {
            throw new IllegalArgumentException("blog.captcha.verify-timeout must be positive");
        }
        if (loginFailuresBeforeCaptcha < 1) {
            throw new IllegalArgumentException("blog.captcha.login-failures-before-captcha must be at least 1");
        }
        if (provider == Provider.TURNSTILE && (isBlank(siteKey) || isBlank(secretKey))) {
            throw new IllegalArgumentException(
                    "blog.captcha.site-key and blog.captcha.secret-key are required for the turnstile provider");
        }
    }

    /** provider만 정한 값(테스트용). */
    public static CaptchaProperties of(Provider provider) {
        return new CaptchaProperties(provider, provider == Provider.TURNSTILE ? "site" : null,
                provider == Provider.TURNSTILE ? "secret" : null, "e2e-pass", Duration.ofSeconds(3),
                "https://challenges.cloudflare.com/turnstile/v0/siteverify", 3);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
