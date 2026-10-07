package net.java21.blog.backend.spam.captcha;

/**
 * CAPTCHA 토큰 검증(005 FR-141, research M9). 결과를 저장하지 않는다. 어느 구현을 쓸지는 {@link CaptchaConfig}가
 * {@code blog.captcha.provider}로 정한다.
 */
public interface CaptchaVerifier {

    /**
     * 토큰을 검증한다.
     *
     * @param token 요청 본문의 {@code captchaToken}(없으면 null)
     * @param ip    요청 IP(Turnstile {@code remoteip}, 없으면 null)
     * @throws net.java21.blog.backend.common.error.BusinessException 400 {@code CAPTCHA_FAILED}
     */
    void verify(String token, String ip);

    /** 이 검증기의 provider(설정 응답용). */
    CaptchaProperties.Provider provider();
}
