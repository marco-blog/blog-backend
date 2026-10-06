package net.java21.blog.backend.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code access_token} HttpOnly 쿠키의 JWT를 검증해 {@code SecurityContext}에 {@link AuthUser}를 넣는다(api-guidelines 8절).
 * {@code Authorization} 헤더는 보지 않는다. 쿠키가 없거나 토큰이 틀리면 아무것도 하지 않고 넘긴다:
 * 공개 경로는 비로그인으로 통과하고, 보호 경로는 인증 진입점이 401 {@code UNAUTHENTICATED}를 준다.
 * {@code SecurityConfig}가 직접 만들어 등록한다(빈으로 두면 서블릿 필터로 한 번 더 등록된다).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";

    private final JwtProvider jwtProvider;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public JwtAuthenticationFilter(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = accessToken(request);
        if (token != null) {
            jwtProvider.verify(token).ifPresent(user -> {
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContext context = contextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                contextHolder.setContext(context);
            });
        }
        chain.doFilter(request, response);
    }

    private static String accessToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
