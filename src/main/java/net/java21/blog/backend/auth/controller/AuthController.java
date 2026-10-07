package net.java21.blog.backend.auth.controller;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import net.java21.blog.backend.auth.dto.LoginRequest;
import net.java21.blog.backend.auth.dto.LoginResponse;
import net.java21.blog.backend.auth.dto.SignupRequest;
import net.java21.blog.backend.auth.dto.SignupResponse;
import net.java21.blog.backend.auth.service.LoginService;
import net.java21.blog.backend.auth.service.RefreshTokenService;
import net.java21.blog.backend.auth.service.SignupService;
import net.java21.blog.backend.auth.web.AuthCookieWriter;
import net.java21.blog.backend.blog.dto.HandleAvailabilityResponse;
import net.java21.blog.backend.blog.service.BlogService;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 가입·로그인·리프레시·로그아웃(contracts/api.md 인증 절, FR-001~007). */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final SignupService signupService;
    private final LoginService loginService;
    private final RefreshTokenService refreshTokenService;
    private final BlogService blogService;
    private final AuthCookieWriter cookieWriter;

    public AuthController(SignupService signupService, LoginService loginService,
            RefreshTokenService refreshTokenService, BlogService blogService, AuthCookieWriter cookieWriter) {
        this.signupService = signupService;
        this.loginService = loginService;
        this.refreshTokenService = refreshTokenService;
        this.blogService = blogService;
        this.cookieWriter = cookieWriter;
    }

    /** 가입 즉시 로그인: 201 + {@code Location: /api/v1/me} + 두 쿠키. */
    @PostMapping("/signup")
    ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request,
            HttpServletRequest httpRequest, HttpServletResponse response) {
        SignupService.Result result = signupService.signup(request, httpRequest.getRemoteAddr());
        cookieWriter.write(response, result.tokens());
        return ResponseEntity.created(URI.create("/api/v1/me")).body(ApiResponse.ok(result.response()));
    }

    @GetMapping("/handle-availability")
    ApiResponse<HandleAvailabilityResponse> handleAvailability(@RequestParam String handle) {
        return ApiResponse.ok(blogService.handleAvailability(handle));
    }

    /** 로그인 기록(FR-139)에 방문자 주소(믿는 프록시가 전달한 값, {@code ClientAddressFilter})와 User-Agent를 남긴다. */
    @PostMapping("/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {
        LoginService.Result result = loginService.login(ClientInfo.of(httpRequest), request);
        cookieWriter.write(response, result.tokens());
        return ApiResponse.ok(result.response());
    }

    /** 리프레시 쿠키로 두 토큰을 새로 받는다(회전). 실패하면 두 쿠키를 지우고 401 {@code REFRESH_INVALID}. */
    @PostMapping("/refresh")
    ApiResponse<Void> refresh(
            @CookieValue(name = AuthCookieWriter.REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            HttpServletResponse response) {
        try {
            cookieWriter.write(response, refreshTokenService.rotate(refreshToken));
        } catch (BusinessException e) {
            cookieWriter.clear(response);
            throw e;
        }
        return ApiResponse.ok();
    }

    /** 로그인 계열을 폐기하고 쿠키를 지운다. 접근 토큰이 만료돼도 리프레시 쿠키로 로그아웃할 수 있다. */
    @PostMapping("/logout")
    ApiResponse<Void> logout(
            @CookieValue(name = AuthCookieWriter.REFRESH_TOKEN_COOKIE, required = false) String refreshToken,
            @CurrentUser(required = false) AuthUser user, HttpServletResponse response) {
        refreshTokenService.logout(refreshToken, user == null ? null : user.familyId());
        cookieWriter.clear(response);
        return ApiResponse.ok();
    }
}
