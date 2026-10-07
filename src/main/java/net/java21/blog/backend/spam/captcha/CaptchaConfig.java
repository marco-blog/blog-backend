package net.java21.blog.backend.spam.captcha;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * provider에 맞는 {@link CaptchaVerifier} 빈 하나(005 research M9). 운영 프로필({@code prod})에서 {@code test}·{@code none}이면 기동을
 * 멈춘다(Turnstile 키가 비면 {@link CaptchaProperties}가 먼저 멈춘다).
 */
@Configuration(proxyBeanMethods = false)
public class CaptchaConfig {

    @Bean
    CaptchaVerifier captchaVerifier(CaptchaProperties properties, Environment environment) {
        return create(properties, environment.acceptsProfiles(Profiles.of("prod")));
    }

    /** provider에 맞는 검증기. 운영이면 Turnstile만 허용한다. */
    static CaptchaVerifier create(CaptchaProperties properties, boolean production) {
        if (production && properties.provider() != CaptchaProperties.Provider.TURNSTILE) {
            throw new IllegalStateException("blog.captcha.provider must be turnstile in the prod profile, but was "
                    + properties.provider().value());
        }
        return switch (properties.provider()) {
            case TURNSTILE -> new TurnstileCaptchaVerifier(properties);
            case TEST -> new TestCaptchaVerifier(properties.testToken());
            case NONE -> new NoopCaptchaVerifier();
        };
    }
}
