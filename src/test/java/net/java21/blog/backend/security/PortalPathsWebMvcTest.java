package net.java21.blog.backend.security;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 003 경로의 공개·로그인·관리자 규칙(T012, contracts/api.md): 주제·포털·릴리스 노트 GET은 비로그인 허용, 끝까지 읽음 POST는
 * 비로그인 허용이지만 Origin 검사를 받고, 마지막 확인 버전은 로그인 필요, 관리자 API는 비로그인·일반 회원 모두 404 {@code NOT_FOUND}.
 */
@WebMvcTest(controllers = PortalPathsTestController.class)
@Import({WebMvcTestSupport.class, PortalPathsTestController.class})
class PortalPathsWebMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/topics", "/api/v1/topics/it-internet/posts?sort=popular", "/api/v1/portal",
            "/api/v1/portal/latest?cursor=abc", "/api/v1/release-notes", "/api/v1/release-notes/search?q=기능",
            "/api/v1/release-notes/1.2.0", "/api/v1/release-notes/1.2.0/revisions",
            "/api/v1/release-notes/1.2.0/revisions/2"})
    void publicReadsAllowAnonymous(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"));
    }

    @Test
    void readCompleteAllowsAnonymousButChecksOrigin() throws Exception {
        mvc.perform(post("/api/v1/posts/12/read-complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"));
        mvc.perform(post("/api/v1/posts/12/read-complete").header("Origin", "https://evil.example.com"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ORIGIN_NOT_ALLOWED"));
    }

    @Test
    void releaseNoteSeenNeedsLogin() throws Exception {
        expect(mvc.perform(post("/api/v1/me/release-notes/seen")), 401, "UNAUTHENTICATED");
        mvc.perform(post("/api/v1/me/release-notes/seen").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/topics", "/api/v1/admin/portal/curations", "/api/v1/admin/portal/exclusions",
            "/api/v1/admin/settings?prefix=portal.", "/api/v1/admin/release-notes"})
    void adminReadsAreHiddenFromAnonymousAndMembers(String path) throws Exception {
        expect(mvc.perform(get(path)), 404, "NOT_FOUND");
        expect(mvc.perform(get(path).cookie(authCookies.user(7L))), 404, "NOT_FOUND");
        expect(mvc.perform(get(path).cookie(authCookies.of(7L, "SUPER_ADMIN"))), 404, "NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/portal/curations", "/api/v1/admin/topics"})
    void adminWritesAreHiddenToo(String path) throws Exception {
        expect(mvc.perform(post(path)), 404, "NOT_FOUND");
        expect(mvc.perform(post(path).cookie(authCookies.user(7L))), 404, "NOT_FOUND");
    }

    private static void expect(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value(code))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }
}
