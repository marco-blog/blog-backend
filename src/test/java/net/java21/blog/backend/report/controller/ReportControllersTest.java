package net.java21.blog.backend.report.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.dto.CreateReportRequest;
import net.java21.blog.backend.report.dto.ReportCreatedResponse;
import net.java21.blog.backend.report.dto.RightsRequestRequest;
import net.java21.blog.backend.report.service.ReportService;
import net.java21.blog.backend.report.service.RightsRequestService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 005 T041: 회원 신고 201 + Location, 비로그인 401, 서비스 오류 코드, 권리 침해 신고 202(비로그인 허용)·요청 검증. */
@WebMvcTest({ReportController.class, RightsRequestController.class})
@Import(WebMvcTestSupport.class)
class ReportControllersTest {

    private static final String RIGHTS = """
            {"targetUrl":"https://blog.java21.net/marco/5","reason":"COPYRIGHT","rightsBasis":"제 글입니다",
             "contactEmail":"me@example.com","captchaToken":"tok"}""";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private ReportService reportService;
    @MockitoBean
    private RightsRequestService rightsRequestService;

    @Test
    void memberReportIsCreated() throws Exception {
        when(reportService.create(7L, new CreateReportRequest("POST", 5L, "SPAM", "광고")))
                .thenReturn(new ReportCreatedResponse(31L, ReportStatus.PENDING));

        mvc.perform(post("/api/v1/reports").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"POST\",\"targetId\":5,\"reason\":\"SPAM\",\"detail\":\"광고\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/reports/31"))
                .andExpect(jsonPath("$.result.id").value(31))
                .andExpect(jsonPath("$.result.status").value("PENDING"));
    }

    @Test
    void memberReportErrors() throws Exception {
        mvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        when(reportService.create(eq(7L), any())).thenThrow(
                new BusinessException(ErrorCode.REPORT_ALREADY_EXISTS, "dup"),
                new BusinessException(ErrorCode.CANNOT_REPORT_OWN_CONTENT, "own"),
                new BusinessException(ErrorCode.REPORT_TARGET_NOT_FOUND, "gone"));
        for (int[] expected : new int[][] {{409}, {422}, {404}}) {
            mvc.perform(post("/api/v1/reports").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"targetType\":\"POST\",\"targetId\":5,\"reason\":\"SPAM\"}"))
                    .andExpect(status().is(expected[0]));
        }
        mvc.perform(post("/api/v1/reports").cookie(authCookies.user(7L)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetId\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("VALIDATION_FAILED"));
    }

    @Test
    void rightsRequestIsAcceptedWithoutLogin() throws Exception {
        mvc.perform(post("/api/v1/rights-requests").contentType(MediaType.APPLICATION_JSON).content(RIGHTS)
                .with(r -> {
                    r.setRemoteAddr("203.0.113.7");
                    return r;
                }))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result").value(nullValue()));
        verify(rightsRequestService).submit(new RightsRequestRequest("https://blog.java21.net/marco/5", "COPYRIGHT",
                "제 글입니다", "me@example.com", "tok"), "203.0.113.7");
    }

    @Test
    void rightsRequestValidation() throws Exception {
        mvc.perform(post("/api/v1/rights-requests").contentType(MediaType.APPLICATION_JSON)
                .content(RIGHTS.replace("me@example.com", "not-an-email")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("contactEmail"))
                .andExpect(jsonPath("$.header.fieldErrors[0].code").value("INVALID_FORMAT"));
        mvc.perform(post("/api/v1/rights-requests").contentType(MediaType.APPLICATION_JSON)
                .content(RIGHTS.replace("제 글입니다", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("rightsBasis"));
        verifyNoInteractions(rightsRequestService);

        doThrow(new BusinessException(ErrorCode.CAPTCHA_FAILED, "bad")).when(rightsRequestService)
                .submit(any(), anyString());
        mvc.perform(post("/api/v1/rights-requests").contentType(MediaType.APPLICATION_JSON).content(RIGHTS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.resultCode").value("CAPTCHA_FAILED"));
        doThrow(new BusinessException(ErrorCode.VALIDATION_FAILED, "x",
                List.of(FieldError.of("reason", "INVALID")))).when(rightsRequestService).submit(any(), anyString());
        mvc.perform(post("/api/v1/rights-requests").contentType(MediaType.APPLICATION_JSON).content(RIGHTS))
                .andExpect(jsonPath("$.header.fieldErrors[0].field").value("reason"));
    }
}
