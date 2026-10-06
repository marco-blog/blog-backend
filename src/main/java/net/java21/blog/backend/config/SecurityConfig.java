package net.java21.blog.backend.config;

import net.java21.blog.backend.common.error.ApiErrorWriter;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.security.OriginCheckFilter;
import net.java21.blog.backend.security.OriginProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfFilter;

/**
 * 기본 보안 설정. 토큰 인증·공개 경로 목록은 인증 기능을 만들 때 더한다.
 * 지금은 health 외 모든 요청에 로그인이 필요하며, 인증·권한 오류도 공통 틀로 응답한다.
 * 상태 변경 요청은 인증보다 먼저 Origin 검사(R3·R27)를 거친다.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(OriginProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter,
            OriginProperties originProperties) throws Exception {
        http
                // 쿠키 인증의 CSRF 방어는 토큰 대신 Origin 검사(OriginCheckFilter)로 한다.
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterBefore(new OriginCheckFilter(originProperties.allowedOrigins(), errorWriter),
                        CsrfFilter.class)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((request, response, ex) ->
                                errorWriter.write(response, ErrorCode.UNAUTHENTICATED, "Authentication required"))
                        .accessDeniedHandler((request, response, ex) ->
                                errorWriter.write(response, ErrorCode.FORBIDDEN, "Access denied")));
        return http.build();
    }
}
