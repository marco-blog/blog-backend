package net.java21.blog.backend.auth.controller;

import jakarta.validation.Valid;

import net.java21.blog.backend.auth.dto.PasswordResetConfirmRequest;
import net.java21.blog.backend.auth.dto.PasswordResetRequest;
import net.java21.blog.backend.auth.service.PasswordResetService;
import net.java21.blog.backend.common.api.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 비밀번호 재설정(contracts/api.md 인증 절, FR-133). 로그인 없이 부른다(Origin 검사는 받는다). */
@RestController
@RequestMapping("/api/v1/auth/password-reset")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /** 가입 여부와 관계없이 항상 202 {@code result: null}. 메일은 커밋 뒤 비동기로 보낸다. */
    @PostMapping("/request")
    ResponseEntity<ApiResponse<Void>> request(@Valid @RequestBody PasswordResetRequest request) {
        passwordResetService.request(request.email());
        return ResponseEntity.accepted().body(ApiResponse.ok());
    }

    /** 새 비밀번호 설정. 200 {@code result: null}(api-guidelines: 204 대신), 모든 로그인 계열 폐기. */
    @PostMapping("/confirm")
    ApiResponse<Void> confirm(@Valid @RequestBody PasswordResetConfirmRequest request) {
        passwordResetService.confirm(request.token(), request.newPassword());
        return ApiResponse.ok();
    }
}
