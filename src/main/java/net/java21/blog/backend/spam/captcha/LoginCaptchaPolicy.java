package net.java21.blog.backend.spam.captcha;

import java.time.Duration;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.spam.RateLimitKind;
import net.java21.blog.backend.spam.RateLimiter;
import org.springframework.stereotype.Component;

/**
 * 로그인 반복 실패 뒤 CAPTCHA(005 FR-141, research M9, 결정 표 20번). 같은 이메일 해시 또는 같은 IP의 연속 실패가
 * {@code blog.captcha.login-failures-before-captcha}(3) 이상이면 다음 로그인에 CAPTCHA가 필요하다(없으면 400
 * {@code CAPTCHA_REQUIRED}, 틀리면 400 {@code CAPTCHA_FAILED}). 카운터는 공용 {@link RateLimiter}(30분 창)이며 로그인에 성공하면 두
 * 카운터를 지운다. 없는 이메일도 센다(가입 여부를 드러내지 않음). 필요하지 않을 때 온 토큰은 검사하지 않는다. 001의 5회 잠금은 그대로다.
 */
@Component
public class LoginCaptchaPolicy {

    static final Duration WINDOW = Duration.ofMinutes(30);

    private final RateLimiter limiter;
    private final CaptchaVerifier verifier;
    private final CaptchaProperties properties;

    public LoginCaptchaPolicy(RateLimiter limiter, CaptchaVerifier verifier, CaptchaProperties properties) {
        this.limiter = limiter;
        this.verifier = verifier;
        this.properties = properties;
    }

    /** 비밀번호를 확인하기 전에 부른다. 필요하면 토큰을 검증한다. */
    public void requireIfNeeded(String emailHash, String ip, String token) {
        if (!required(emailHash, ip)) {
            return;
        }
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.CAPTCHA_REQUIRED, "Captcha required after repeated login failures");
        }
        verifier.verify(token, ip);
    }

    /** 지금 CAPTCHA가 필요한지. */
    public boolean required(String emailHash, String ip) {
        int threshold = properties.loginFailuresBeforeCaptcha();
        return limiter.count(RateLimitKind.LOGIN_FAILURE, emailKey(emailHash)) >= threshold
                || limiter.count(RateLimitKind.LOGIN_FAILURE, ipKey(ip)) >= threshold;
    }

    /** 실패 한 번(이메일 해시·IP 모두). */
    public void recordFailure(String emailHash, String ip) {
        limiter.tryAcquire(RateLimitKind.LOGIN_FAILURE, emailKey(emailHash), Integer.MAX_VALUE, WINDOW);
        limiter.tryAcquire(RateLimitKind.LOGIN_FAILURE, ipKey(ip), Integer.MAX_VALUE, WINDOW);
    }

    /** 성공하면 두 카운터를 지운다. */
    public void reset(String emailHash, String ip) {
        limiter.reset(RateLimitKind.LOGIN_FAILURE, emailKey(emailHash));
        limiter.reset(RateLimitKind.LOGIN_FAILURE, ipKey(ip));
    }

    private static String emailKey(String emailHash) {
        return "e:" + emailHash;
    }

    private static String ipKey(String ip) {
        return "ip:" + (ip == null ? "" : ip);
    }
}
