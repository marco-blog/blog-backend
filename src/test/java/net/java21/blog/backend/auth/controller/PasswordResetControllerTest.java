package net.java21.blog.backend.auth.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.java21.blog.backend.auth.service.PasswordResetService;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 비밀번호 재설정 API(T131, FR-133): 요청은 가입 여부와 관계없이 항상 같은 202,
 * 확인은 200 {@code result: null}(api-guidelines: 204 대신) 또는 400. 로그인 없이 부른다.
 */
@WebMvcTest(PasswordResetController.class)
@Import(WebMvcTestSupport.class)
class PasswordResetControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PasswordResetService passwordResetService;

    @Test
    void requestIsAlways202WithoutLogin() throws Exception {
        for (String email : new String[] {"marco@example.com", "nobody@example.com"}) {
            mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + email + "\"}"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.header.isSuccessful").value(true))
                    .andExpect(jsonPath("$.result").value(nullValue()));
            verify(passwordResetService).request(email);
        }
    }

    @Test
    void requestValidation() throws Exception {
        mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("email"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("INVALID_FORMAT"));
        mvc.perform(post("/api/v1/auth/password-reset/request").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("REQUIRED"));
        verifyNoInteractions(passwordResetService);
    }

    @Test
    void confirmIs200WithNullResult() throws Exception {
        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"raw-token\",\"newPassword\":\"newPassword1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(passwordResetService).confirm("raw-token", "newPassword1");
    }

    @Test
    void confirmErrors() throws Exception {
        doThrow(new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID, "x"))
                .when(passwordResetService).confirm(any(), any());
        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"used\",\"newPassword\":\"newPassword1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("PASSWORD_RESET_TOKEN_INVALID"));

        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\",\"newPassword\":\"weak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("PASSWORD_WEAK"));

        mvc.perform(post("/api/v1/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"newPassword1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("token"));
    }

    @Test
    void stateChangesStillNeedAllowedOrigin() throws Exception {
        mvc.perform(post("/api/v1/auth/password-reset/request").header("Origin", "https://evil.example")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"marco@example.com\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ORIGIN_NOT_ALLOWED"));
    }
}
