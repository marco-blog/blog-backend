package net.java21.blog.backend.security;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 007 T039: 외부 썸네일·원문 이동 GET은 비로그인 허용, 회원 외부 블로그 API는 로그인 필요(401), 관리자 API는 관리자 외 404.
 */
@WebMvcTest(controllers = ExternalPathsTestController.class)
@Import({WebMvcTestSupport.class, ExternalPathsTestController.class})
class ExternalPathsWebMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @ParameterizedTest
    @ValueSource(strings = {"/media/external/AbCdEfGhIjKlMnOpQrStUv", "/api/v1/external-posts/12/visit"})
    void publicReadsAllowAnonymous(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me/external-blogs", "/api/v1/me/external-blogs/3",
            "/api/v1/me/external-blogs/3/posts"})
    void memberReadsNeedLogin(String path) throws Exception {
        expect(mvc.perform(get(path)), 401, "UNAUTHENTICATED");
        mvc.perform(get(path).cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/external-blog-previews", "/api/v1/me/external-blog-verifications",
            "/api/v1/me/external-blog-verifications/4/check", "/api/v1/me/external-blogs",
            "/api/v1/external-blogs/3/claim"})
    void memberWritesNeedLoginAndOrigin(String path) throws Exception {
        expect(mvc.perform(post(path)), 401, "UNAUTHENTICATED");
        mvc.perform(post(path).cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(7));
        mvc.perform(post(path).cookie(authCookies.user(7L)).header("Origin", "https://evil.example.com"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ORIGIN_NOT_ALLOWED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/external-blogs", "/api/v1/admin/external-blogs/3"})
    void adminReadsAreHidden(String path) throws Exception {
        expect(mvc.perform(get(path)), 404, "NOT_FOUND");
        expect(mvc.perform(get(path).cookie(authCookies.user(7L))), 404, "NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/admin/external-blogs", "/api/v1/admin/external-blogs/3/approve"})
    void adminWritesAreHidden(String path) throws Exception {
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
