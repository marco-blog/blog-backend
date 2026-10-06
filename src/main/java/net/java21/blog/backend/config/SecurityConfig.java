package net.java21.blog.backend.config;

import java.time.Clock;

import net.java21.blog.backend.common.error.ApiErrorWriter;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.security.JwtAuthenticationFilter;
import net.java21.blog.backend.security.JwtProvider;
import net.java21.blog.backend.security.OriginCheckFilter;
import net.java21.blog.backend.security.OriginProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * 보안 설정(research R2·R3, api-guidelines 8절).
 * <ol>
 *   <li>상태 변경 요청은 먼저 Origin 검사({@link OriginCheckFilter}, R3·R27)를 거친다.</li>
 *   <li>{@code access_token} 쿠키의 JWT로 인증한다({@link JwtAuthenticationFilter}). {@code Authorization} 헤더는 쓰지 않는다.</li>
 *   <li>{@link #PUBLIC_GET} 경로의 GET과 {@link #PUBLIC_POST}(가입·로그인·리프레시·로그아웃)만 비로그인으로 허용하고, 나머지는 로그인이 필요하다.
 *       인증·권한 오류도 공통 틀(401 {@code UNAUTHENTICATED}, 403 {@code FORBIDDEN})로 응답한다.</li>
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
            "/api/v1/tags/**",
            "/api/v1/legal/**",
            "/media/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/actuator/health"
    };

    /** {@link #PUBLIC_GET} 아래에 있지만 주인만 쓰는 GET 경로(로그인 필요). 공개 규칙보다 먼저 검사한다. */
    static final String[] AUTHENTICATED_GET = {
            "/api/v1/blogs/*/posts/drafts/**"
    };

    /**
     * 비로그인으로 부를 수 있는 POST 경로(Origin 검사는 그대로 받는다). 로그아웃은 접근 토큰이 만료돼도
     * 리프레시 쿠키로 계열을 폐기할 수 있게 연다. 조회수는 SSR loader가 방문자 대신 부른다.
     */
    static final String[] PUBLIC_POST = {
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/posts/*/views"
    };

    @Bean
    JwtProvider jwtProvider(AuthProperties authProperties, Clock clock) {
        return new JwtProvider(authProperties, clock);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter,
            OriginProperties originProperties, JwtProvider jwtProvider) throws Exception {
        http
                // 쿠키 인증의 CSRF 방어는 토큰 대신 Origin 검사(OriginCheckFilter)로 한다.
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new OriginCheckFilter(originProperties.allowedOrigins(), errorWriter),
                        CsrfFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtProvider), AnonymousAuthenticationFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.GET, AUTHENTICATED_GET).authenticated()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_POST).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                errorWriter.write(response, ErrorCode.UNAUTHENTICATED, "Authentication required"))
                        .accessDeniedHandler((request, response, ex) ->
                                errorWriter.write(response, ErrorCode.FORBIDDEN, "Access denied")));
        return http.build();
    }
}
