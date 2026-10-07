package net.java21.blog.backend.security;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

/** 005 T012: 신고·CAPTCHA·트랙백·관리자 경로의 공개·로그인·관리자 규칙. */
@WebMvcTest(controllers = ModerationPathsTestController.class)
@Import({WebMvcTestSupport.class, ModerationPathsTestController.class})
class ModerationPathsWebMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/captcha/config", "/api/v1/posts/12/trackbacks?page=0"})
    void publicReadsAllowAnonymous(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ok"));
    }

    @Test
    void rightsRequestAllowsAnonymousButChecksOrigin() throws Exception {
        mvc.perform(post("/api/v1/rights-requests")).andExpect(status().isOk());
        expect(mvc.perform(post("/api/v1/rights-requests").header("Origin", "https://evil.example.com")), 403,
                "ORIGIN_NOT_ALLOWED");
    }

    @Test
    void trackbackReceiveAllowsAnonymousWithoutOrigin() throws Exception {
        mvc.perform(post("/marco/12/trackback").header("Origin", "https://other-blog.example"))
                .andExpect(status().isOk());
        mvc.perform(post("/marco/abc/trackback")).andExpect(status().is4xxClientError());
    }

    @Test
    void memberAndOwnerPathsNeedLogin() throws Exception {
        expect(mvc.perform(post("/api/v1/reports")), 401, "UNAUTHENTICATED");
        expect(mvc.perform(delete("/api/v1/trackbacks/3")), 401, "UNAUTHENTICATED");
        expect(mvc.perform(get("/api/v1/posts/3/trackback-pings")), 401, "UNAUTHENTICATED");
        expect(mvc.perform(get("/api/v1/blogs/marco/manage/trackbacks")), 401, "UNAUTHENTICATED");
        mvc.perform(post("/api/v1/reports").cookie(authCookies.user(7L))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/reports", "/api/v1/admin/reports/summary",
            "/api/v1/admin/contents/hidden-posts", "/api/v1/admin/users?q=ab", "/api/v1/admin/banned-words"})
    void adminReadsAreHiddenFromAnonymousAndMembers(String path) throws Exception {
        expect(mvc.perform(get(path)), 404, "NOT_FOUND");
        expect(mvc.perform(get(path).cookie(authCookies.user(7L))), 404, "NOT_FOUND");
    }

    @Test
    void adminWritesAreHiddenToo() throws Exception {
        expect(mvc.perform(post("/api/v1/admin/reports/1/resolve").cookie(authCookies.user(7L))), 404, "NOT_FOUND");
        expect(mvc.perform(post("/api/v1/admin/users/1/suspend")), 404, "NOT_FOUND");
        expect(mvc.perform(post("/api/v1/admin/banned-words").cookie(authCookies.user(7L))), 404, "NOT_FOUND");
        expect(mvc.perform(delete("/api/v1/admin/contents/posts/1/hidden").cookie(authCookies.user(7L))), 404,
                "NOT_FOUND");
    }

    private static void expect(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value(code))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }
}
