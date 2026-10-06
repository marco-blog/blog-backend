package net.java21.blog.backend.admin;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.java21.blog.backend.common.error.ApiErrorWriter;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.security.AuthUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 관리자 API 접근 규칙(T159, contracts/api.md "관리자 API 공통 규칙", 006 FR-097).
 * {@code /api/v1/admin/**} 요청은 JWT 인증 뒤, 권한 검사 전에 여기서 DB의 현재 {@code role}(ADMIN·SUPER_ADMIN)과
 * {@code status}(ACTIVE)를 다시 확인한다({@link AdminRoleLookup}). 비로그인·일반 회원·권한이 회수된 회원은 401·403이 아니라
 * 404 {@code NOT_FOUND}(공통 틀)로 응답해 관리 API가 있는지 드러내지 않는다. JWT의 role 클레임은 보지 않는다.
 * 경로는 Spring MVC와 같은 방식(퍼센트 인코딩을 푼 경로 패턴)으로 맞춰 인코딩으로 우회하지 못하게 한다.
 * {@code SecurityConfig}가 직접 만들어 등록한다(빈으로 두면 서블릿 필터로 한 번 더 등록된다).
 */
public class AdminAccessFilter extends OncePerRequestFilter {

    public static final String ADMIN_PATHS = "/api/v1/admin/**";

    private final RequestMatcher adminPaths = PathPatternRequestMatcher.withDefaults().matcher(ADMIN_PATHS);
    private final AdminRoleLookup roleLookup;
    private final ApiErrorWriter errorWriter;

    public AdminAccessFilter(AdminRoleLookup roleLookup, ApiErrorWriter errorWriter) {
        this.roleLookup = roleLookup;
        this.errorWriter = errorWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !adminPaths.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser user
                && roleLookup.isActiveAdmin(user.userId())) {
            chain.doFilter(request, response);
            return;
        }
        errorWriter.write(response, ErrorCode.NOT_FOUND, "Not found");
    }
}
