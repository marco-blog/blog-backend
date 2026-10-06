package net.java21.blog.backend.legal.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import net.java21.blog.backend.legal.LegalDocumentType;
import net.java21.blog.backend.legal.dto.LegalDocumentResponse;
import net.java21.blog.backend.legal.service.LegalService;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/v1/legal/terms|privacy?lang=}(T129, FR-137·155): 비로그인 허용, 공통 틀의 result. */
@WebMvcTest(LegalController.class)
@Import(WebMvcTestSupport.class)
class LegalControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private LegalService legalService;

    @Test
    void termsForAnonymous() throws Exception {
        when(legalService.document(LegalDocumentType.TERMS, "ja")).thenReturn(new LegalDocumentResponse(
                "2026-10-06", "en", "ko", LocalDate.of(2026, 10, 6), "<h1>Terms</h1>"));

        mvc.perform(get("/api/v1/legal/terms").param("lang", "ja"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.resultCode").value("OK"))
                .andExpect(jsonPath("$.result.version").value("2026-10-06"))
                .andExpect(jsonPath("$.result.lang").value("en"))
                .andExpect(jsonPath("$.result.authoritativeLang").value("ko"))
                .andExpect(jsonPath("$.result.effectiveAt").value("2026-10-06"))
                .andExpect(jsonPath("$.result.contentHtml").value("<h1>Terms</h1>"));
    }

    @Test
    void privacyWithoutLang() throws Exception {
        when(legalService.document(LegalDocumentType.PRIVACY, null)).thenReturn(new LegalDocumentResponse(
                "2026-10-06", "en", "ko", null, "<h1>Privacy</h1>"));

        mvc.perform(get("/api/v1/legal/privacy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.lang").value("en"))
                .andExpect(jsonPath("$.result.contentHtml").value("<h1>Privacy</h1>"));
    }
}
