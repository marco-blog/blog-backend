package net.java21.blog.backend.config;

import java.time.Clock;

import net.java21.blog.backend.admin.AdminAccessFilter;
import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.common.error.ApiErrorWriter;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.security.JwtAuthenticationFilter;
import net.java21.blog.backend.security.JwtProvider;
import net.java21.blog.backend.security.OriginCheckFilter;
import net.java21.blog.backend.security.OriginProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * 보안 설정(research R2·R3, api-guidelines 8절).
 * <ol>
 *   <li>상태 변경 요청은 먼저 Origin 검사({@link OriginCheckFilter}, R3·R27)를 거친다.</li>
 *   <li>{@code access_token} 쿠키의 JWT로 인증한다({@link JwtAuthenticationFilter}). {@code Authorization} 헤더는 쓰지 않는다.</li>
 *   <li>{@link #PUBLIC_GET} 경로의 GET과 {@link #PUBLIC_POST}(가입·로그인·리프레시·로그아웃·비밀번호 재설정)만 비로그인으로 허용하고, 나머지는 로그인이 필요하다.
 *       인증·권한 오류도 공통 틀(401 {@code UNAUTHENTICATED}, 403 {@code FORBIDDEN})로 응답한다.</li>
 *   <li>관리자 API({@code /api/v1/admin/**})는 인증 뒤 {@link AdminAccessFilter}가 요청마다 DB의 현재 권한을 확인하고,
 *       관리자가 아니면(비로그인 포함) 404 {@code NOT_FOUND}로 응답한다(006 FR-097). 권한 확인 빈이 없으면 모두 거부한다.</li>
 * </ol>
 * 공개 GET의 내용별 노출(비공개 글 404 등)은 각 서비스가 data-model "글 노출 매트릭스"로 판단한다.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({OriginProperties.class, AuthProperties.class})
public class SecurityConfig {

    /** 비로그인으로 읽을 수 있는 GET 경로. 기능을 더할 때 contracts/api.md의 "비로그인" 행에 맞춰 여기에만 더한다. */
    static final String[] PUBLIC_GET = {
            "/api/v1/blogs/**",
            "/api/v1/auth/handle-availability",
            "/api/v1/posts/{id}",
            "/api/v1/posts/{id}/comments",
            "/api/v1/tags/**",
            "/api/v1/legal/**",
            // 002 검색·관련 글(contracts/api.md), 블로그 피드(RSS·Atom)·사이트맵·robots(front 서버가 프록시)
            "/api/v1/search/**",
            "/api/v1/posts/{id}/related",
            "/*/rss",
            "/*/atom",
            "/*/category/*/rss",
            "/sitemap.xml",
            "/sitemap/**",
            "/robots.txt",
            // 003 포털·주제·릴리스 노트(003 contracts/api.md)
            "/api/v1/topics",
            "/api/v1/topics/*/posts",
            "/api/v1/portal",
            "/api/v1/portal/latest",
            "/api/v1/release-notes",
            "/api/v1/release-notes/**",
            "/media/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    /** {@link #PUBLIC_GET} 아래에 있지만 주인만 쓰는 GET 경로(로그인 필요). 공개 규칙보다 먼저 검사한다. */
    static final String[] AUTHENTICATED_GET = {
            "/api/v1/blogs/*/posts/drafts/**",
            "/api/v1/blogs/*/manage/**",
            // 004 내보내기·차단 목록(004 contracts/api.md): 주인만
            "/api/v1/blogs/*/exports/**",
            "/api/v1/blogs/*/blocks"
    };

    /**
     * 비로그인으로 부를 수 있는 POST 경로(Origin 검사는 그대로 받는다). 로그아웃은 접근 토큰이 만료돼도
     * 리프레시 쿠키로 계열을 폐기할 수 있게 연다. 조회수는 SSR loader가 방문자 대신, 끝까지 읽음은 브라우저가 부른다.
     */
    static final String[] PUBLIC_POST = {
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/auth/password-reset/request",
            "/api/v1/auth/password-reset/confirm",
            "/api/v1/posts/*/views",
            // 003 끝까지 읽음(FR-086): 방문자 브라우저가 보낸다
            "/api/v1/posts/*/read-complete",
            // 004 방문 기록·방명록·보호 글 열기·댓글(비회원 허용 여부는 서비스가 판단)·비밀 글 열기
            "/api/v1/blogs/*/visits",
            "/api/v1/blogs/*/guestbook",
            "/api/v1/posts/*/unlock",
            "/api/v1/posts/*/comments",
            "/api/v1/comments/*/unlock",
            "/api/v1/guestbook-entries/*/unlock"
    };

    /** 004 비회원도 비밀번호로 고치는 PATCH 경로(Origin 검사는 받는다). 회원·비회원 판단은 서비스가 한다. */
    static final String[] PUBLIC_PATCH = {
            "/api/v1/comments/*",
            "/api/v1/guestbook-entries/*"
    };

    /** 004 비회원도 비밀번호로 지우는 DELETE 경로(Origin 검사는 받는다). 회원·비회원 판단은 서비스가 한다. */
    static final String[] PUBLIC_DELETE = {
            "/api/v1/comments/*",
            "/api/v1/guestbook-entries/*"
    };

    @Bean
    JwtProvider jwtProvider(AuthProperties authProperties, Clock clock) {
        return new JwtProvider(authProperties, clock);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter,
            OriginProperties originProperties, JwtProvider jwtProvider, ObjectProvider<AdminRoleLookup> adminRoleLookup)
            throws Exception {
        AdminRoleLookup roleLookup = adminRoleLookup.getIfAvailable(() -> userId -> false);
        http
                // 쿠키 인증의 CSRF 방어는 토큰 대신 Origin 검사(OriginCheckFilter)로 한다.
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new OriginCheckFilter(originProperties.allowedOrigins(), errorWriter),
                        CsrfFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), AnonymousAuthenticationFilter.class)
                .addFilterBefore(new AdminAccessFilter(roleLookup, errorWriter), AuthorizationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, AUTHENTICATED_GET).authenticated()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .requestMatchers(HttpMethod.PATCH, PUBLIC_PATCH).permitAll()
                        .requestMatchers(HttpMethod.DELETE, PUBLIC_DELETE).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                errorWriter.write(response, ErrorCode.UNAUTHENTICATED, "Authentication required"))
                        .accessDeniedHandler((request, response, ex) ->
                                errorWriter.write(response, ErrorCode.FORBIDDEN, "Access denied")));
        return http.build();
    }
}
