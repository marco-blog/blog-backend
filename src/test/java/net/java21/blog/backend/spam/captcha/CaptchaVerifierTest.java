package net.java21.blog.backend.spam.captcha;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;

import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.StubHttpServer;
import org.junit.jupiter.api.Test;

/** 005 T009: Turnstile·test·none 검증기. */
class CaptchaVerifierTest {

    private static CaptchaProperties turnstile(StubHttpServer server, Duration timeout) {
        return new CaptchaProperties(CaptchaProperties.Provider.TURNSTILE, "site", "s3cret", "e2e-pass", timeout,
                server.uri("/siteverify").toString(), 3);
    }

    @Test
    void turnstilePassesOnSuccessAndSendsSecretResponseAndIp() {
        try (StubHttpServer server = StubHttpServer.start()) {
            server.respond("/siteverify", 200, "application/json", "{\"success\":true}");
            CaptchaVerifier verifier = new TurnstileCaptchaVerifier(turnstile(server, Duration.ofSeconds(3)));

            assertThatCode(() -> verifier.verify("tok en", "203.0.113.9")).doesNotThrowAnyException();

            assertThat(verifier.provider()).isEqualTo(CaptchaProperties.Provider.TURNSTILE);
            assertThat(server.requests()).singleElement().satisfies(r -> {
                assertThat(r.method()).isEqualTo("POST");
                assertThat(r.body()).isEqualTo("secret=s3cret&response=tok+en&remoteip=203.0.113.9");
                assertThat(r.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
            });
        }
    }

    @Test
    void turnstileRejectsFailureErrorsAndBadBodies() {
        try (StubHttpServer server = StubHttpServer.start()) {
            CaptchaVerifier verifier = new TurnstileCaptchaVerifier(turnstile(server, Duration.ofSeconds(3)));

            server.respond("/siteverify", 200, "application/json", "{\"success\":false}");
            assertCode(() -> verifier.verify("t", null), ErrorCode.CAPTCHA_FAILED);
            server.respond("/siteverify", 500, "application/json", "{\"success\":true}");
            assertCode(() -> verifier.verify("t", null), ErrorCode.CAPTCHA_FAILED);
            server.respond("/siteverify", 200, "application/json", "not json{");
            assertCode(() -> verifier.verify("t", ""), ErrorCode.CAPTCHA_FAILED);
            assertThat(server.requests()).hasSize(3).allSatisfy(r -> assertThat(r.body()).doesNotContain("remoteip"));
        }
    }

    @Test
    void turnstileTimesOut() {
        try (StubHttpServer server = StubHttpServer.start()) {
            server.respond("/siteverify", 200, "application/json", "{\"success\":true}", Duration.ofSeconds(2));
            CaptchaVerifier verifier = new TurnstileCaptchaVerifier(turnstile(server, Duration.ofMillis(300)));
            assertCode(() -> verifier.verify("t", null), ErrorCode.CAPTCHA_FAILED);
        }
    }

    @Test
    void turnstileSkipsCallWithoutToken() {
        try (StubHttpServer server = StubHttpServer.start()) {
            CaptchaVerifier verifier = new TurnstileCaptchaVerifier(turnstile(server, Duration.ofSeconds(3)));
            assertCode(() -> verifier.verify(null, null), ErrorCode.CAPTCHA_FAILED);
            assertCode(() -> verifier.verify("  ", null), ErrorCode.CAPTCHA_FAILED);
            assertCode(() -> verifier.verify("x".repeat(3000), null), ErrorCode.CAPTCHA_FAILED);
            assertThat(server.requests()).isEmpty();
        }
    }

    @Test
    void turnstileUnreachableFails() {
        CaptchaProperties properties = new CaptchaProperties(CaptchaProperties.Provider.TURNSTILE, "site", "s",
                "e2e-pass", Duration.ofMillis(500), "http://127.0.0.1:1/siteverify", 3);
        assertCode(() -> new TurnstileCaptchaVerifier(properties).verify("t", null), ErrorCode.CAPTCHA_FAILED);
    }

    @Test
    void testVerifierAcceptsOnlyConfiguredToken() {
        CaptchaVerifier verifier = new TestCaptchaVerifier("e2e-pass");
        assertThatCode(() -> verifier.verify("e2e-pass", null)).doesNotThrowAnyException();
        assertCode(() -> verifier.verify("wrong", null), ErrorCode.CAPTCHA_FAILED);
        assertCode(() -> verifier.verify(null, null), ErrorCode.CAPTCHA_FAILED);
        assertThat(verifier.provider()).isEqualTo(CaptchaProperties.Provider.TEST);
    }

    @Test
    void noopVerifierAcceptsAnything() {
        CaptchaVerifier verifier = new NoopCaptchaVerifier();
        assertThatCode(() -> verifier.verify(null, null)).doesNotThrowAnyException();
        assertThat(verifier.provider()).isEqualTo(CaptchaProperties.Provider.NONE);
    }
}
