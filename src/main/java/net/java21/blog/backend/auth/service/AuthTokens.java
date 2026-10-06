package net.java21.blog.backend.auth.service;

import java.time.Duration;

/**
 * 로그인·가입·리프레시로 발급한 두 토큰과 쿠키 수명. 리프레시 토큰 원문은 쿠키로만 내보내고 저장하지 않는다.
 *
 * @param refreshMaxAge 리프레시 쿠키 수명: 유휴 만료와 절대 만료 중 이른 시각까지
 */
public record AuthTokens(String accessToken, Duration accessMaxAge, String refreshToken, Duration refreshMaxAge) {

    /** 로그·디버그 출력에 토큰 원문이 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "AuthTokens[accessToken=****, accessMaxAge=" + accessMaxAge + ", refreshToken=****, refreshMaxAge="
                + refreshMaxAge + "]";
    }
}
