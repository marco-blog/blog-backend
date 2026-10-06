package net.java21.blog.backend.user.controller;

import java.util.List;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import net.java21.blog.backend.auth.web.AuthCookieWriter;
import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.common.api.PageRequests;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import net.java21.blog.backend.user.dto.ChangePasswordRequest;
import net.java21.blog.backend.user.dto.LoginHistoryResponse;
import net.java21.blog.backend.user.dto.MeResponse;
import net.java21.blog.backend.user.dto.UpdateMeRequest;
import net.java21.blog.backend.user.dto.WithdrawRequest;
import net.java21.blog.backend.user.service.AccountService;
import net.java21.blog.backend.user.service.LoginHistoryService;
import net.java21.blog.backend.user.service.MeQueryService;
import net.java21.blog.backend.user.service.PasswordChangeService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 회원 본인({@code /me}, contracts/api.md 회원·계정 설정 절, FR-008·009·082·139). */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    /** 로그인 기록은 최신순 고정. 정렬 매개변수는 받지 않는다. */
    private static final PageRequests LOGIN_HISTORY = PageRequests.sortableBy(LoginHistoryService.DEFAULT_SORT);

    private final MeQueryService meQueryService;
    private final AccountService accountService;
    private final PasswordChangeService passwordChangeService;
    private final LoginHistoryService loginHistoryService;
    private final AuthCookieWriter cookieWriter;

    public MeController(MeQueryService meQueryService, AccountService accountService,
            PasswordChangeService passwordChangeService, LoginHistoryService loginHistoryService,
            AuthCookieWriter cookieWriter) {
        this.meQueryService = meQueryService;
        this.accountService = accountService;
        this.passwordChangeService = passwordChangeService;
        this.loginHistoryService = loginHistoryService;
        this.cookieWriter = cookieWriter;
    }

    @GetMapping
    ApiResponse<MeResponse> me(@CurrentUser AuthUser user) {
        return ApiResponse.ok(meQueryService.me(user.userId()));
    }

    /** 프로필·언어·시간대 수정(보낸 필드만). 응답은 {@code GET /me}와 같다. */
    @PatchMapping
    ApiResponse<MeResponse> update(@CurrentUser AuthUser user, @Valid @RequestBody UpdateMeRequest request) {
        accountService.updateProfile(user.userId(), request);
        return ApiResponse.ok(meQueryService.me(user.userId()));
    }

    /** 탈퇴(되돌릴 수 없음). 성공하면 이 브라우저의 인증 쿠키도 지운다. */
    @DeleteMapping
    ApiResponse<Void> withdraw(@CurrentUser AuthUser user, @Valid @RequestBody WithdrawRequest request,
            HttpServletResponse response) {
        accountService.withdraw(user.userId(), request.password());
        cookieWriter.clear(response);
        return ApiResponse.ok();
    }

    /** 비밀번호 변경. 이 기기(접근 토큰의 로그인 계열)를 뺀 모든 기기의 로그인을 끊는다. */
    @PutMapping("/password")
    ApiResponse<Void> changePassword(@CurrentUser AuthUser user, @Valid @RequestBody ChangePasswordRequest request) {
        passwordChangeService.change(user.userId(), user.familyId(), request.currentPassword(),
                request.newPassword());
        return ApiResponse.ok();
    }

    /** 최근 로그인 기록(최신순, IP 일부 가림). {@code page}는 0부터, {@code size} 기본 20·최대 50. */
    @GetMapping("/login-history")
    ApiResponse<List<LoginHistoryResponse>> loginHistory(@CurrentUser AuthUser user,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return ApiResponse.page(loginHistoryService.list(user.userId(), LOGIN_HISTORY.resolve(page, size, null)));
    }
}
