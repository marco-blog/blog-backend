package net.java21.blog.backend.spam.captcha;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 005 T009: blog.captcha.* 기본값, prod에서 test·none·빈 키 기동 실패. */
class CaptchaPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties(CaptchaProperties.class)
    @Import(CaptchaConfig.class)
    static class Config {
    }

    @Test
    void defaultsToNone() {
        runner.run(context -> {
            CaptchaProperties properties = context.getBean(CaptchaProperties.class);
            assertThat(properties.provider()).isEqualTo(CaptchaProperties.Provider.NONE);
            assertThat(properties.testToken()).isEqualTo("e2e-pass");
            assertThat(properties.verifyTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.loginFailuresBeforeCaptcha()).isEqualTo(3);
            assertThat(context.getBean(CaptchaVerifier.class)).isInstanceOf(NoopCaptchaVerifier.class);
        });
    }

    @Test
    void providerSelectsVerifier() {
        runner.withPropertyValues("blog.captcha.provider=test").run(context -> assertThat(
                context.getBean(CaptchaVerifier.class)).isInstanceOf(TestCaptchaVerifier.class));
        runner.withPropertyValues("blog.captcha.provider=turnstile", "blog.captcha.site-key=s",
                "blog.captcha.secret-key=k").run(context -> assertThat(context.getBean(CaptchaVerifier.class))
                        .isInstanceOf(TurnstileCaptchaVerifier.class));
    }

    @Test
    void turnstileRequiresKeysAndInvalidValuesFail() {
        runner.withPropertyValues("blog.captcha.provider=turnstile").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.captcha.provider=turnstile", "blog.captcha.site-key=s",
                "blog.captcha.secret-key= ").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.captcha.verify-timeout=0s").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.captcha.login-failures-before-captcha=0").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.captcha.test-token= ").run(c -> assertThat(c).hasFailed());
    }

    @Test
    void prodProfileAllowsOnlyTurnstile() {
        runner.withPropertyValues("spring.profiles.active=prod").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("spring.profiles.active=prod", "blog.captcha.provider=test")
                .run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("spring.profiles.active=prod", "blog.captcha.provider=turnstile",
                "blog.captcha.site-key=s", "blog.captcha.secret-key=k").run(c -> assertThat(c).hasNotFailed());
        assertThatThrownBy(() -> CaptchaConfig.create(CaptchaProperties.of(CaptchaProperties.Provider.NONE), true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(CaptchaProperties.Provider.TURNSTILE.value()).isEqualTo("turnstile");
    }
}
