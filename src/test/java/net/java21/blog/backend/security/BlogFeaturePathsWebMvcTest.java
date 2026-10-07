package net.java21.blog.backend.security;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 004 경로의 공개·로그인 규칙(T012, 004 contracts/api.md): 방명록·사이드바·보관함·공지 GET과 방문·방명록·열기·댓글 POST,
 * 댓글·방명록 PATCH·DELETE는 비로그인 허용(쓰기는 Origin 검사를 받음), 내보내기·차단·관리 GET은 로그인 필요.
 */
@WebMvcTest(controllers = BlogFeaturePathsTestController.class)
@Import({WebMvcTestSupport.class, BlogFeaturePathsTestController.class})
class BlogFeaturePathsWebMvcTest {

    private static final String EVIL = "https://evil.example.com";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/blogs/marco/guestbook", "/api/v1/blogs/marco/sidebar",
            "/api/v1/blogs/marco/archive?year=2026", "/api/v1/blogs/marco/notices"})
    void publicReadsAllowAnonymous(String path) throws Exception {
        ok(mvc.perform(get(path)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/blogs/marco/visits", "/api/v1/blogs/marco/guestbook", "/api/v1/posts/12/unlock",
            "/api/v1/posts/12/comments", "/api/v1/comments/3/unlock", "/api/v1/guestbook-entries/4/unlock"})
    void publicPostsAllowAnonymousButCheckOrigin(String path) throws Exception {
        anonymousButOriginChecked(post(path), post(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/comments/3", "/api/v1/guestbook-entries/4"})
    void guestEditsAllowAnonymousButCheckOrigin(String path) throws Exception {
        anonymousButOriginChecked(patch(path), patch(path));
        anonymousButOriginChecked(delete(path), delete(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/blogs/marco/exports", "/api/v1/blogs/marco/exports/5/file",
            "/api/v1/blogs/marco/blocks", "/api/v1/blogs/marco/manage/sidebar",
            "/api/v1/blogs/marco/manage/stats?from=2026-01-01"})
    void ownerReadsNeedLogin(String path) throws Exception {
        expect(mvc.perform(get(path)), 401, "UNAUTHENTICATED");
        ok(mvc.perform(get(path).cookie(authCookies.user(7L))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/blogs/marco/exports", "/api/v1/blogs/marco/blocks"})
    void ownerWritesNeedLogin(String path) throws Exception {
        expect(mvc.perform(post(path)), 401, "UNAUTHENTICATED");
        ok(mvc.perform(post(path).cookie(authCookies.user(7L))));
    }

    private void anonymousButOriginChecked(MockHttpServletRequestBuilder plain, MockHttpServletRequestBuilder evil)
            throws Exception {
        ok(mvc.perform(plain));
        expect(mvc.perform(evil.header("Origin", EVIL)), 403, "ORIGIN_NOT_ALLOWED");
    }

    private static void ok(ResultActions result) throws Exception {
        result.andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ok"));
    }

    private static void expect(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value(code))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }
}
