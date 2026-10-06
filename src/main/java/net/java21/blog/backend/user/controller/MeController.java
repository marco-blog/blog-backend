package net.java21.blog.backend.user.controller;

import net.java21.blog.backend.common.api.ApiResponse;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.CurrentUser;
import net.java21.blog.backend.user.dto.MeResponse;
import net.java21.blog.backend.user.service.MeQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 회원 본인({@code /me}, contracts/api.md 회원 절). 계정 설정 API는 Phase 4에서 더한다. */
@RestController
public class MeController {

    private final MeQueryService meQueryService;

    public MeController(MeQueryService meQueryService) {
        this.meQueryService = meQueryService;
    }

    @GetMapping("/api/v1/me")
    ApiResponse<MeResponse> me(@CurrentUser AuthUser user) {
        return ApiResponse.ok(meQueryService.me(user.userId()));
    }
}
