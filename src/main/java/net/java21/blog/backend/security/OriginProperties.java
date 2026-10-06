package net.java21.blog.backend.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 상태 변경 요청에 허용하는 {@code Origin} 목록(001 research R3·R27).
 * 운영은 {@code https://blog.java21.net}, 개발은 front 개발 서버 주소.
 */
@ConfigurationProperties("blog.security")
public record OriginProperties(List<String> allowedOrigins) {
}
