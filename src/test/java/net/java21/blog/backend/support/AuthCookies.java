package net.java21.blog.backend.support;

import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.http.Cookie;

import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.security.JwtAuthenticationFilter;
import net.java21.blog.backend.security.JwtProvider;
import org.springframework.boot.test.context.TestComponent;

/**
 * 테스트용 {@code access_token} 쿠키를 만든다. 컨텍스트의 {@link JwtProvider}(test 프로필의 고정 테스트 키)로 서명하므로
 * {@code JwtAuthenticationFilter}가 그대로 인증한다. {@link WebMvcTestSupport}가 빈으로 올린다.
 *
 * <pre>{@code
 * mvc.perform(get("/api/v1/me").cookie(authCookies.user(1L)))
 * }</pre>
 */
@TestComponent
public class AuthCookies {

    public static final String FAMILY_ID = "00000000-0000-0000-0000-000000000001";

    private final JwtProvider jwtProvider;
    private final AuthProperties properties;
    private final Clock clock;

    public AuthCookies(JwtProvider jwtProvider, AuthProperties properties, Clock clock) {
        this.jwtProvider = jwtProvider;
        this.properties = properties;
        this.clock = clock;
    }

    /** 일반 회원(USER). */
    public Cookie user(long userId) {
        return of(userId, "USER");
    }

    public Cookie of(long userId, String role) {
        return cookie(jwtProvider.issue(userId, role, FAMILY_ID));
    }

    /** 이미 만료된 토큰(만료 시각이 1분 지남). */
    public Cookie expired(long userId) {
        Clock past = Clock.offset(clock, properties.accessTtl().plus(Duration.ofMinutes(1)).negated());
        return cookie(new JwtProvider(properties, past).issue(userId, "USER", FAMILY_ID));
    }

    public static Cookie cookie(String token) {
        return new Cookie(JwtAuthenticationFilter.ACCESS_TOKEN_COOKIE, token);
    }
}
