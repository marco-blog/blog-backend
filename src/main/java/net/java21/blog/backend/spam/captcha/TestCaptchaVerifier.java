package net.java21.blog.backend.spam.captcha;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * {@code test}: 설정한 시험 토큰({@code blog.captcha.test-token}, 기본 {@code e2e-pass})만 통과(E2E, research M9). 운영 프로필에서는 쓸 수
 * 없다({@link CaptchaConfig}).
 */
public class TestCaptchaVerifier implements CaptchaVerifier {

    private final byte[] expected;

    public TestCaptchaVerifier(String testToken) {
        this.expected = testToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void verify(String token, String ip) {
        if (CaptchaFailures.isBlank(token)
                || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
            throw CaptchaFailures.failed("test token mismatch");
        }
    }

    @Override
    public CaptchaProperties.Provider provider() {
        return CaptchaProperties.Provider.TEST;
    }
}
