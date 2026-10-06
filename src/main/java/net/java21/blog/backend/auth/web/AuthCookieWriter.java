package net.java21.blog.backend.auth.web;

import java.time.Duration;

import jakarta.servlet.http.HttpServletResponse;

import net.java21.blog.backend.auth.service.AuthTokens;
import net.java21.blog.backend.security.JwtAuthenticationFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 인증 쿠키 작성(research R2, tasks.md "구현 전 결정 사항" 1번).
 * {@code access_token}·{@code refresh_token} 모두 {@code Path=/; HttpOnly; Secure; SameSite=Lax}.
 * 리프레시 쿠키도 {@code Path=/}여야 SSR 요청에 실려 front 서버가 401 때 대신 갱신할 수 있다.
 */
@Component
public class AuthCookieWriter {

    public static final String ACCESS_TOKEN_COOKIE = JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE;
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    public void write(HttpServletResponse response, AuthTokens tokens) {
        add(response, ACCESS_TOKEN_COOKIE, tokens.accessToken(), tokens.accessMaxAge());
        add(response, REFRESH_TOKEN_COOKIE, tokens.refreshToken(), tokens.refreshMaxAge());
    }

    /** 두 쿠키를 지운다(로그아웃, 리프레시 실패). */
    public void clear(HttpServletResponse response) {
        add(response, ACCESS_TOKEN_COOKIE, "", Duration.ZERO);
        add(response, REFRESH_TOKEN_COOKIE, "", Duration.ZERO);
    }

    private static void add(HttpServletResponse response, String name, String value, Duration maxAge) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .maxAge(maxAge.isNegative() ? Duration.ZERO : maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
