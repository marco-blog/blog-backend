package net.java21.blog.backend.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.Cookie;

import net.java21.blog.backend.auth.dto.LoginRequest;
import net.java21.blog.backend.auth.dto.LoginResponse;
import net.java21.blog.backend.auth.dto.SignupRequest;
import net.java21.blog.backend.auth.dto.SignupResponse;
import net.java21.blog.backend.auth.service.AuthTokens;
import net.java21.blog.backend.auth.service.LoginService;
import net.java21.blog.backend.auth.service.RefreshTokenService;
import net.java21.blog.backend.auth.service.SignupService;
import net.java21.blog.backend.auth.web.AuthCookieWriter;
import net.java21.blog.backend.blog.dto.BlogLink;
import net.java21.blog.backend.blog.dto.HandleAvailabilityResponse;
import net.java21.blog.backend.blog.service.BlogService;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** 인증 API(T054): 상태 코드·응답 틀·쿠키 속성·오류 코드(contracts/api.md 인증 절). */
@WebMvcTest(AuthController.class)
@Import({WebMvcTestSupport.class, AuthCookieWriter.class})
class AuthControllerTest {

    private static final AuthTokens TOKENS = new AuthTokens("access-jwt", Duration.ofMinutes(30), "refresh-raw",
            Duration.ofHours(4));

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @MockitoBean
    private SignupService signupService;
    @MockitoBean
    private LoginService loginService;
    @MockitoBean
    private RefreshTokenService refreshTokenService;
    @MockitoBean
    private BlogService blogService;

    private static String signupJson(String password) {
        return """
                {"email":"marco@example.com","password":"%s","nickname":"마르코","handle":"marco",
                 "agreeTerms":true,"agreePrivacy":true,"over14":true,"termsVersion":"2026-10-06",
                 "locale":"ko","timeZone":"Asia/Seoul"}""".formatted(password);
    }

    /** 005 T070: 가입·로그인의 captchaToken 바인딩, CAPTCHA 400과 가입 한도 429(Retry-After). */
    @Test
    void captchaTokenIsBoundAndSpamErrorsKeepTheirStatus() throws Exception {
        when(signupService.signup(any(SignupRequest.class), any()))
                .thenThrow(new BusinessException(ErrorCode.CAPTCHA_FAILED, "captcha"))
                .thenThrow(BusinessException.retryAfter(ErrorCode.TOO_MANY_REQUESTS, "slow", 3600));
        String body = signupJson("password1").replace("\"timeZone\":\"Asia/Seoul\"",
                "\"timeZone\":\"Asia/Seoul\",\"captchaToken\":\"e2e-pass\"");
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("CAPTCHA_FAILED"));
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3600"));
        org.mockito.ArgumentCaptor<SignupRequest> signup = org.mockito.ArgumentCaptor.forClass(SignupRequest.class);
        verify(signupService, org.mockito.Mockito.times(2)).signup(signup.capture(), eq("127.0.0.1"));
        assertThat(signup.getValue().captchaToken()).isEqualTo("e2e-pass");

        when(loginService.login(any(), any(LoginRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.CAPTCHA_REQUIRED, "required"));
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.com\",\"password\":\"x\",\"captchaToken\":\"tok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("CAPTCHA_REQUIRED"));
        verify(loginService).login(any(), eq(new LoginRequest("a@b.com", "x", "tok")));
    }

    @Test
    void signupIs201WithLocationBodyAndBothCookies() throws Exception {
        when(signupService.signup(any(SignupRequest.class), any()))
                .thenReturn(new SignupService.Result(new SignupResponse(42L, "marco"), TOKENS));

        MvcResult result = mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(signupJson("password1")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/me"))
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.resultCode").value("OK"))
                .andExpect(jsonPath("$.result.userId").value(42))
                .andExpect(jsonPath("$.result.handle").value("marco"))
                .andReturn();

        List<String> cookies = result.getResponse().getHeaders("Set-Cookie");
        assertThat(cookies).anySatisfy(c -> assertThat(c)
                .startsWith("access_token=access-jwt;")
                .contains("Path=/;", "Max-Age=1800", "Secure", "HttpOnly", "SameSite=Lax"));
        assertThat(cookies).anySatisfy(c -> assertThat(c)
                .startsWith("refresh_token=refresh-raw;")
                .contains("Path=/;", "Max-Age=14400", "Secure", "HttpOnly", "SameSite=Lax"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"short1", "abcdefghij", "1234567890", "a1aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void weakPasswordIsFieldErrorPasswordWeak(String password) throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content(signupJson(password)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("PASSWORD_WEAK"))
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(signupService, never()).signup(any(), any());
    }

    @Test
    void signupRequiresFields() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.header.fieldErrors[*].field", hasItem("password")))
                .andExpect(jsonPath("$.header.fieldErrors[*].field", hasItem("handle")))
                .andExpect(jsonPath("$.header.fieldErrors[*].field", hasItem("agreeTerms")))
                .andExpect(jsonPath("$.header.fieldErrors[*].code", hasItem("REQUIRED")));
    }

    @ParameterizedTest
    @CsvSource({
            "EMAIL_TAKEN, 409", "HANDLE_TAKEN, 409", "HANDLE_RESERVED, 422", "HANDLE_INVALID, 422",
            "TERMS_VERSION_OUTDATED, 422", "VALIDATION_FAILED, 400"})
    void signupErrorsUseContractStatus(ErrorCode code, int httpStatus) throws Exception {
        when(signupService.signup(any(), any())).thenThrow(new BusinessException(code, "x"));
        expectError(mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(signupJson("password1"))), httpStatus, code.name());
    }

    @Test
    void signupRequiresAllowedOrigin() throws Exception {
        mvc.perform(post("/api/v1/auth/signup").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content(signupJson("password1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ORIGIN_NOT_ALLOWED"));
    }

    @ParameterizedTest
    @CsvSource({"TAKEN, marco", "RESERVED, admin", "INVALID, a"})
    void handleAvailabilityReasons(HandleAvailabilityResponse.Reason reason, String handle) throws Exception {
        when(blogService.handleAvailability(handle)).thenReturn(HandleAvailabilityResponse.unavailable(reason));
        mvc.perform(get("/api/v1/auth/handle-availability").param("handle", handle))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.available").value(false))
                .andExpect(jsonPath("$.result.reason").value(reason.name()));
    }

    @Test
    void handleAvailabilityAvailableHasNoReasonAndIsPublic() throws Exception {
        when(blogService.handleAvailability("free")).thenReturn(HandleAvailabilityResponse.ofAvailable());
        mvc.perform(get("/api/v1/auth/handle-availability").param("handle", "free"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.available").value(true))
                .andExpect(jsonPath("$.result.reason").doesNotExist());
        mvc.perform(get("/api/v1/auth/handle-availability"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("handle"));
    }

    @Test
    void loginReturnsMemberAndBlogsWithCookies() throws Exception {
        when(loginService.login(any(), any(LoginRequest.class))).thenReturn(new LoginService.Result(
                new LoginResponse(7L, "마르코", "USER",
                        List.of(new BlogLink("marco", "마르코의 블로그"), new BlogLink("marco-dev", "개발"))),
                TOKENS));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"marco@example.com\",\"password\":\"password1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.userId").value(7))
                .andExpect(jsonPath("$.result.nickname").value("마르코"))
                .andExpect(jsonPath("$.result.role").value("USER"))
                .andExpect(jsonPath("$.result.blogs[0].handle").value("marco"))
                .andExpect(jsonPath("$.result.blogs[1].title").value("개발"))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("access_token=access-jwt"))))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("refresh_token=refresh-raw"))));
    }

    @ParameterizedTest
    @CsvSource({"INVALID_CREDENTIALS, 401", "ACCOUNT_LOCKED, 423"})
    void loginErrors(ErrorCode code, int httpStatus) throws Exception {
        when(loginService.login(any(), any())).thenThrow(new BusinessException(code, "x"));
        expectError(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"marco@example.com\",\"password\":\"password1\"}")), httpStatus, code.name());
    }

    /** 로그인 기록(T139)에 남길 방문자 주소와 User-Agent를 서비스에 넘긴다. */
    @Test
    void loginPassesClientAddressAndUserAgent() throws Exception {
        when(loginService.login(any(), any(LoginRequest.class))).thenReturn(new LoginService.Result(
                new LoginResponse(7L, "마르코", "USER", List.of()), TOKENS));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("User-Agent", "Mozilla/5.0 Test")
                        .with(request -> {
                            request.setRemoteAddr("211.234.56.78");
                            return request;
                        })
                        .content("{\"email\":\"marco@example.com\",\"password\":\"password1\"}"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(loginService).login(
                org.mockito.ArgumentMatchers.eq(new net.java21.blog.backend.common.web.ClientInfo(
                        "211.234.56.78", "Mozilla/5.0 Test")),
                any(LoginRequest.class));
    }

    @Test
    void loginValidation() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"));
    }

    @Test
    void refreshRotatesCookiesAndReturnsNullResult() throws Exception {
        when(refreshTokenService.rotate("old-refresh")).thenReturn(TOKENS);

        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("refresh_token", "old-refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("access_token=access-jwt"))))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("refresh_token=refresh-raw"))));
    }

    @Test
    void refreshFailureIs401AndClearsCookies() throws Exception {
        when(refreshTokenService.rotate(isNull()))
                .thenThrow(new BusinessException(ErrorCode.REFRESH_INVALID, "Missing refresh token"));

        expectError(mvc.perform(post("/api/v1/auth/refresh")), 401, "REFRESH_INVALID")
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("refresh_token=;"))))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("Max-Age=0"))));
    }

    @Test
    void logoutRevokesFamilyAndDeletesCookies() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").cookie(authCookies.user(7L), new Cookie("refresh_token", "r1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(nullValue()))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("access_token=;"))))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("refresh_token=;"))))
                .andExpect(header().stringValues("Set-Cookie", hasItem(containsString("Max-Age=0"))));
        verify(refreshTokenService).logout("r1", AuthCookies.FAMILY_ID);
    }

    @Test
    void logoutWorksWithOnlyRefreshCookie() throws Exception {
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("refresh_token", "r1")))
                .andExpect(status().isOk());
        verify(refreshTokenService).logout(eq("r1"), isNull());
    }

    @Test
    void authEndpointsOtherThanPublicOnesNeedLogin() throws Exception {
        mvc.perform(get("/api/v1/auth/login"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"));
        verify(refreshTokenService, never()).logout(anyString(), anyString());
    }

    private static ResultActions expectError(ResultActions actions, int httpStatus, String code) throws Exception {
        return actions.andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value(code))
                .andExpect(jsonPath("$.header.traceId").exists())
                .andExpect(jsonPath("$.result").value(nullValue()));
    }
}
