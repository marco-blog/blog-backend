package net.java21.blog.backend.spam.captcha;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;

/** CAPTCHA 실패 예외. */
final class CaptchaFailures {

    private CaptchaFailures() {
    }

    static BusinessException failed(String reason) {
        return new BusinessException(ErrorCode.CAPTCHA_FAILED, "Captcha verification failed: " + reason);
    }

    static boolean isBlank(String token) {
        return token == null || token.isBlank();
    }
}
