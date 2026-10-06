package net.java21.blog.backend.user.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import net.java21.blog.backend.blog.dto.BlogLink;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import net.java21.blog.backend.user.dto.MeResponse;
import net.java21.blog.backend.user.service.MeQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** {@code GET /api/v1/me}(T094): 회원 기본 정보 + 내 블로그, {@code unseenReleaseNote}는 null. */
@WebMvcTest(MeController.class)
@Import(WebMvcTestSupport.class)
class MeControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @MockitoBean
    private MeQueryService meQueryService;

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
}
