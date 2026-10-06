package net.java21.blog.backend.security;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.java21.blog.backend.common.error.ApiErrorWriter;
import net.java21.blog.backend.common.error.ErrorCode;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * CSRF 방어(001 research R3, R27): 쿠키 인증이므로 상태 변경 요청(POST/PUT/PATCH/DELETE)의
 * {@code Origin} 헤더가 허용 목록에 있어야 한다. 헤더가 없거나 목록 밖이면 403 {@code ORIGIN_NOT_ALLOWED}.
 * {@code Referer}로 대신 판단하지 않는다.
 * 예외 경로는 {@link #EXEMPT_PATHS} 한 곳에서만 관리한다.
 */
public class OriginCheckFilter extends OncePerRequestFilter {

    /** Origin 검사를 하지 않는 경로. 쿠키 인증을 쓰지 않는 외부 수신 엔드포인트만 둔다. */
    static final List<PathPattern> EXEMPT_PATHS = List.of(
            // 트랙백 핑 수신(005, R16): 외부 블로그 서버가 보낸다. 출처 IP 속도 제한과 입력 검증으로 보호.
            PathPatternParser.defaultInstance.parse("/{handle}/{postId:\\d+}/trackback"));

    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final Set<String> allowedOrigins;
    private final ApiErrorWriter errorWriter;

    public OriginCheckFilter(Collection<String> allowedOrigins, ApiErrorWriter errorWriter) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalStateException("blog.security.allowed-origins is empty");
        }
        this.allowedOrigins = allowedOrigins.stream().map(OriginCheckFilter::normalize).collect(Collectors.toUnmodifiableSet());
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!STATE_CHANGING.contains(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        PathContainer container = PathContainer.parsePath(path);
        return EXEMPT_PATHS.stream().anyMatch(p -> p.matches(container));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin == null || !allowedOrigins.contains(normalize(origin))) {
            errorWriter.write(response, ErrorCode.ORIGIN_NOT_ALLOWED, "Origin not allowed");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String normalize(String origin) {
        String value = origin.strip().toLowerCase(Locale.ROOT);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
