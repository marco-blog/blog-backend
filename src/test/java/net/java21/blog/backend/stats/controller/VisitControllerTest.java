package net.java21.blog.backend.stats.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.stats.service.VisitService;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 방문 기록 API(T057): 비로그인 200 {@code null}, 방문자 쿠키 발급, 없는 블로그 404. */
@WebMvcTest(VisitController.class)
@Import(WebMvcTestSupport.class)
class VisitControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private VisitService visitService;

    @Test
    void anonymousVisitIssuesVisitorCookie() throws Exception {
        mvc.perform(post("/api/v1/blogs/marco/visits").header(HttpHeaders.USER_AGENT, "Mozilla/5.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")));
        verify(visitService).record(eq("marco"), isNull(), startsWith("v:"), eq("Mozilla/5.0"));
    }

    @Test
    void memberVisitUsesMemberKey() throws Exception {
        mvc.perform(post("/api/v1/blogs/marco/visits").cookie(authCookies.user(7L)))
                .andExpect(status().isOk());
        verify(visitService).record("marco", 7L, "u:7", null);
    }

    @Test
    void missingBlogIs404() throws Exception {
        when(visitService.record(eq("ghost"), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "x"));
        mvc.perform(post("/api/v1/blogs/ghost/visits"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.header.resultCode").value("BLOG_NOT_FOUND"));
    }
}
