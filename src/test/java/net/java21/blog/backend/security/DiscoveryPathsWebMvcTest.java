package net.java21.blog.backend.security;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * 002 경로의 공개·로그인 규칙(T007, contracts/api.md): 검색·관련 글·피드·사이트맵·robots는 비로그인 GET 허용,
 * 구독 피드·알림·좋아요·구독은 로그인 필요(401 {@code UNAUTHENTICATED} 공통 틀), 상태 변경(PUT·DELETE)은 Origin 검사.
 */
@WebMvcTest(controllers = DiscoveryPathsTestController.class)
@Import({WebMvcTestSupport.class, DiscoveryPathsTestController.class})
class DiscoveryPathsWebMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/search/posts?q=spring", "/api/v1/posts/12/related", "/marco/rss", "/marco/atom",
            "/marco/category/12/rss", "/sitemap.xml", "/sitemap/posts-1.xml", "/sitemap/pages.xml", "/robots.txt"})
    void publicReadsAllowAnonymous(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ok"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me/feed", "/api/v1/me/notifications"})
    void myReadsNeedLogin(String path) throws Exception {
        expectUnauthenticated(mvc.perform(get(path)));
        mvc.perform(get(path).cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me/likes/12", "/api/v1/me/subscriptions/marco"})
    void likeAndSubscriptionWritesNeedLogin(String path) throws Exception {
        expectUnauthenticated(mvc.perform(put(path)));
        expectUnauthenticated(mvc.perform(delete(path)));
        mvc.perform(put(path).cookie(authCookies.user(7L))).andExpect(jsonPath("$.result").value(7));
        mvc.perform(delete(path).cookie(authCookies.user(7L))).andExpect(jsonPath("$.result").value(7));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me/likes/12", "/api/v1/me/subscriptions/marco"})
    void likeAndSubscriptionWritesCheckOrigin(String path) throws Exception {
        expectOriginRejected(put(path));
        expectOriginRejected(delete(path));
    }

    private void expectOriginRejected(MockHttpServletRequestBuilder request) throws Exception {
        mvc.perform(request.header("Origin", "https://evil.example.com").cookie(authCookies.user(7L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ORIGIN_NOT_ALLOWED"));
    }

    private static void expectUnauthenticated(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.result").value(nullValue()));
    }
}
