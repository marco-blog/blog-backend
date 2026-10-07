package net.java21.blog.backend.spam.captcha;

/** {@code none}: 검증하지 않는다(로컬 개발). 운영 프로필에서는 쓸 수 없다({@link CaptchaConfig}). */
public class NoopCaptchaVerifier implements CaptchaVerifier {

    @Override
    public void verify(String token, String ip) {
        // 검증하지 않는다.
    }

    @Override
    public CaptchaProperties.Provider provider() {
        return CaptchaProperties.Provider.NONE;
    }
}
