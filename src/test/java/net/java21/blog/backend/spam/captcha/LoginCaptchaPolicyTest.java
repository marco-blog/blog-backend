package net.java21.blog.backend.spam.captcha;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.spam.RateLimiter;
import org.junit.jupiter.api.Test;

/** 005 T068: 같은 이메일 해시 또는 같은 IP 연속 3회 실패 뒤 CAPTCHA, 성공하면 초기화, 30분 창(FR-141). */
class LoginCaptchaPolicyTest {

    private final AtomicLong nanos = new AtomicLong();
    private final LoginCaptchaPolicy policy = new LoginCaptchaPolicy(new RateLimiter(nanos::get),
            new TestCaptchaVerifier("e2e-pass"), CaptchaProperties.of(CaptchaProperties.Provider.TEST));

    @Test
    void requiredAfterThreeFailuresByEmailOrIp() {
        policy.requireIfNeeded("h1", "1.1.1.1", null);
        policy.recordFailure("h1", "1.1.1.1");
        policy.recordFailure("h1", "2.2.2.2");
        assertThat(policy.required("h1", "9.9.9.9")).isFalse();
        policy.recordFailure("h1", "3.3.3.3");
        assertThat(policy.required("h1", "9.9.9.9")).isTrue();
        assertThat(policy.required("h2", "9.9.9.9")).isFalse();

        policy.recordFailure("a", "4.4.4.4");
        policy.recordFailure("b", "4.4.4.4");
        policy.recordFailure("c", "4.4.4.4");
        assertThat(policy.required("other", "4.4.4.4")).isTrue();
    }

    @Test
    void tokenIsCheckedOnlyWhenRequired() {
        policy.requireIfNeeded("h1", "1.1.1.1", "anything");
        for (int i = 0; i < 3; i++) {
            policy.recordFailure("h1", "1.1.1.1");
        }
        assertCode(() -> policy.requireIfNeeded("h1", "1.1.1.1", null), ErrorCode.CAPTCHA_REQUIRED);
        assertCode(() -> policy.requireIfNeeded("h1", "1.1.1.1", " "), ErrorCode.CAPTCHA_REQUIRED);
        assertCode(() -> policy.requireIfNeeded("h1", "1.1.1.1", "wrong"), ErrorCode.CAPTCHA_FAILED);
        policy.requireIfNeeded("h1", "1.1.1.1", "e2e-pass");
    }

    @Test
    void successResetsBothCountersAndWindowExpires() {
        for (int i = 0; i < 3; i++) {
            policy.recordFailure("h1", null);
        }
        policy.reset("h1", null);
        assertThat(policy.required("h1", null)).isFalse();

        for (int i = 0; i < 3; i++) {
            policy.recordFailure("h2", "5.5.5.5");
        }
        nanos.addAndGet(Duration.ofMinutes(30).toNanos());
        assertThat(policy.required("h2", "5.5.5.5")).isFalse();
    }
}
