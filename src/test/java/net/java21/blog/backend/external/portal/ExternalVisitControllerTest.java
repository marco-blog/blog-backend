package net.java21.blog.backend.external.portal;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 007 T047: {@code GET /api/v1/external-posts/{id}/visit} — 302 {@code Location} = 저장된 링크, {@code Cache-Control: no-store},
 * {@code Referrer-Policy: no-referrer}, 비로그인 허용, 방문자 키(회원·쿠키·IP 해시), 노출 아님·숫자 아닌 id는 404 (FR-124, research E14).
 */
@WebMvcTest(ExternalVisitController.class)
@Import({WebMvcTestSupport.class, ExternalVisitControllerTest.Hasher.class})
class ExternalVisitControllerTest {

    static class Hasher {
        @Bean
        PersonalDataHasher personalDataHasher() {
            return TestEntities.HASHER;
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private ExternalClickService clickService;

    @Test
    void redirectsAnonymousVisitorToOriginalWithoutReferrer() throws Exception {
        when(clickService.visit(eq(7L), startsWith("ip:"))).thenReturn("https://dev.example/posts/1?a=b");

        mvc.perform(get("/api/v1/external-posts/7/visit"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://dev.example/posts/1?a=b"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void visitorCookieAndMemberAreTheirOwnKeys() throws Exception {
        when(clickService.visit(anyLong(), org.mockito.ArgumentMatchers.anyString())).thenReturn("https://a.example/");

        mvc.perform(get("/api/v1/external-posts/7/visit").cookie(new Cookie("visitor_id", "abcdefgh-1234")))
                .andExpect(status().isFound());
        verify(clickService).visit(7L, "v:abcdefgh-1234");

        mvc.perform(get("/api/v1/external-posts/8/visit").cookie(authCookies.user(42L)))
                .andExpect(status().isFound());
        verify(clickService).visit(8L, "u:42");
    }

    @Test
    void invisiblePostIs404() throws Exception {
        when(clickService.visit(eq(9L), org.mockito.ArgumentMatchers.anyString())).thenThrow(
                new BusinessException(ErrorCode.EXTERNAL_POST_NOT_FOUND, "x"));

        mvc.perform(get("/api/v1/external-posts/9/visit"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_POST_NOT_FOUND"));
    }

    @Test
    void nonNumericIdIs404WithoutLookup() throws Exception {
        for (String id : new String[] {"abc", "0", "-1", "12345678901234567890"}) {
            mvc.perform(get("/api/v1/external-posts/" + id + "/visit"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.header.resultCode").value("EXTERNAL_POST_NOT_FOUND"));
        }
        verifyNoInteractions(clickService);
    }
}
