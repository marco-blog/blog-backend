package net.java21.blog.backend.user.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.auth.web.AuthCookieWriter;
import net.java21.blog.backend.blog.dto.BlogLink;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import net.java21.blog.backend.user.dto.LoginHistoryResponse;
import net.java21.blog.backend.user.dto.MeResponse;
import net.java21.blog.backend.user.service.AccountService;
import net.java21.blog.backend.user.service.LoginHistoryService;
import net.java21.blog.backend.user.service.MeQueryService;
import net.java21.blog.backend.user.service.PasswordChangeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /api/v1/me}(T094): 회원 기본 정보 + 내 블로그, {@code unseenReleaseNote}는 null.
 * 계정 설정(T130): {@code PATCH /me}, {@code DELETE /me} {@code { password }}, {@code PUT /me/password},
 * {@code GET /me/login-history?page=}의 형식·401·검증 오류.
 */
@WebMvcTest(MeController.class)
@Import({WebMvcTestSupport.class, AuthCookieWriter.class})
class MeControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @MockitoBean
    private MeQueryService meQueryService;
    @MockitoBean
    private AccountService accountService;
    @MockitoBean
    private PasswordChangeService passwordChangeService;
    @MockitoBean
    private LoginHistoryService loginHistoryService;

    private static MeResponse marco(String nickname) {
        return new MeResponse(7L, "marco@example.com", nickname, "소개", null, "USER", "ja", "Asia/Tokyo",
                List.of(new BlogLink("marco", "마르코의 블로그")), null);
    }

    @Test
    void returnsMemberWithBlogs() throws Exception {
        when(meQueryService.me(7L)).thenReturn(new MeResponse(7L, "marco@example.com", "마르코", null, null, "USER",
                "ko", "Asia/Seoul", List.of(new BlogLink("marco", "마르코의 블로그")), null));

        mvc.perform(get("/api/v1/me").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.userId").value(7))
                .andExpect(jsonPath("$.result.email").value("marco@example.com"))
                .andExpect(jsonPath("$.result.nickname").value("마르코"))
                .andExpect(jsonPath("$.result.bio").value(nullValue()))
                .andExpect(jsonPath("$.result.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.result.role").value("USER"))
                .andExpect(jsonPath("$.result.locale").value("ko"))
                .andExpect(jsonPath("$.result.timeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.result.blogs[0].handle").value("marco"))
                .andExpect(jsonPath("$.result.blogs[0].title").value("마르코의 블로그"))
                .andExpect(jsonPath("$.result.unseenReleaseNote").value(nullValue()))
                .andExpect(jsonPath("$.result.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.result.emailHash").doesNotExist());
    }

    @Test
    void needsLogin() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/me").cookie(authCookies.expired(7L)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inactiveMemberIs401() throws Exception {
        when(meQueryService.me(7L)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED, "inactive"));
        mvc.perform(get("/api/v1/me").cookie(authCookies.user(7L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
    }

    @Test
    void patchMeUpdatesSentFieldsAndReturnsMe() throws Exception {
        when(meQueryService.me(7L)).thenReturn(marco("새 닉네임"));

        mvc.perform(patch("/api/v1/me").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"새 닉네임\",\"locale\":\"ja\",\"timeZone\":\"Asia/Tokyo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.nickname").value("새 닉네임"))
                .andExpect(jsonPath("$.result.locale").value("ja"))
                .andExpect(jsonPath("$.result.timeZone").value("Asia/Tokyo"));

        verify(accountService).updateProfile(eq(7L), argThat(r -> r.hasNickname() && r.hasLocale()
                && r.hasTimeZone() && !r.hasBio() && "새 닉네임".equals(r.getNickname())));
    }

    @Test
    void patchMeValidation() throws Exception {
        mvc.perform(patch("/api/v1/me").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"" + "가".repeat(31) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("nickname"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("TOO_LONG"))
                .andExpect(jsonPath("$.header.fieldErrors[0].params.max").value(30));
        verifyNoInteractions(accountService);

        doThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "x",
                List.of(net.java21.blog.backend.common.api.FieldError.of("locale", "INVALID"))))
                .when(accountService).updateProfile(eq(7L), any());
        mvc.perform(patch("/api/v1/me").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locale\":\"fr\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("locale"));
    }

    @Test
    void withdrawClearsCookiesAndReturnsNullResult() throws Exception {
        mvc.perform(delete("/api/v1/me").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("access_token=;"))))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("refresh_token=;"))));

        verify(accountService).withdraw(7L, "password1");
    }

    @Test
    void withdrawErrors() throws Exception {
        mvc.perform(delete("/api/v1/me").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("REQUIRED"));

        doThrow(new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH, "x"))
                .when(accountService).withdraw(7L, "wrong");
        mvc.perform(delete("/api/v1/me").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("CURRENT_PASSWORD_MISMATCH"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void changePasswordKeepsCurrentFamily() throws Exception {
        mvc.perform(put("/api/v1/me/password").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password1\",\"newPassword\":\"newPassword2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(nullValue()));

        verify(passwordChangeService).change(7L, AuthCookies.FAMILY_ID, "password1", "newPassword2");
    }

    @Test
    void changePasswordErrors() throws Exception {
        mvc.perform(put("/api/v1/me/password").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"password1\",\"newPassword\":\"weak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("PASSWORD_WEAK"));

        doThrow(new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH, "x"))
                .when(passwordChangeService).change(eq(7L), any(), eq("wrong"), any());
        mvc.perform(put("/api/v1/me/password").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"newPassword2\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("CURRENT_PASSWORD_MISMATCH"));
    }

    @Test
    void loginHistoryIsPagedWithMaskedIp() throws Exception {
        Instant at = Instant.parse("2026-10-06T04:24:19Z");
        when(loginHistoryService.list(7L, PageRequest.of(1, 20, LoginHistoryService.DEFAULT_SORT)))
                .thenReturn(new PageImpl<>(List.of(new LoginHistoryResponse(at, false, "211.234.*.*", "Mozilla/5.0")),
                        PageRequest.of(1, 20), 21));

        mvc.perform(get("/api/v1/me/login-history").param("page", "1").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(21))
                .andExpect(jsonPath("$.result[0].at").value("2026-10-06T04:24:19Z"))
                .andExpect(jsonPath("$.result[0].success").value(false))
                .andExpect(jsonPath("$.result[0].ipMasked").value("211.234.*.*"))
                .andExpect(jsonPath("$.result[0].device").value("Mozilla/5.0"))
                .andExpect(jsonPath("$.result[0].ip").doesNotExist());

        mvc.perform(get("/api/v1/me/login-history").param("page", "-1").cookie(authCookies.user(7L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("page"));
    }

    @Test
    void accountSettingsNeedLogin() throws Exception {
        mvc.perform(patch("/api/v1/me").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/me").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"p\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/v1/me/password").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me/login-history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
        verifyNoInteractions(accountService, passwordChangeService, loginHistoryService);
    }
}
