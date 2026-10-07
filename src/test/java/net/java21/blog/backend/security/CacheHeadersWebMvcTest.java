package net.java21.blog.backend.security;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import net.java21.blog.backend.admin.AdminRoleLookup;
import net.java21.blog.backend.support.AuthCookies;
import net.java21.blog.backend.support.WebMvcTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 로그인이 필요한 API 응답의 {@code Cache-Control: no-store}(T241, api-guidelines 8절).
 * 실제 보안 설정({@code SecurityConfig})의 기본 캐시 헤더(Spring Security {@code no-cache, no-store, max-age=0, must-revalidate})가
 * 성공·오류(401·403) 응답 모두에 붙고, 스스로 Cache-Control을 정한 응답(이미지의 긴 캐시)은 덮어쓰지 않는지 본다.
 */
@WebMvcTest(controllers = CacheHeadersTestController.class)
@Import({WebMvcTestSupport.class, CacheHeadersTestController.class})
class CacheHeadersWebMvcTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AuthCookies authCookies;
    @MockitoBean
    private AdminRoleLookup roleLookup;

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me", "/api/v1/me/blogs", "/api/v1/blogs/marco/manage/posts",
            "/api/v1/blogs/marco/posts/drafts/latest"})
    void authenticatedReadsAreNotStored(String path) throws Exception {
        mvc.perform(get(path).cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void authenticatedWritesAreNotStored() throws Exception {
        mvc.perform(patch("/api/v1/me").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mvc.perform(post("/api/v1/blogs/marco/posts/drafts").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void publicReadWithLoginIsNotStored() throws Exception {
        mvc.perform(get("/api/v1/posts/1").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void authenticationAndOriginErrorsAreNotStored() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mvc.perform(get("/api/v1/me").cookie(authCookies.expired(7L)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        mvc.perform(patch("/api/v1/me").header("Origin", "https://evil.example.com").cookie(authCookies.user(7L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.header.resultCode").value("ORIGIN_NOT_ALLOWED"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void responsesThatSetTheirOwnCacheControlKeepIt() throws Exception {
        mvc.perform(get("/media/k3Jd9fQ2xLmA7pZ0bR5tYw"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me/feed", "/api/v1/me/notifications"})
    void discoveryReadsOfMyResourcesAreNotStored(String path) throws Exception {
        mvc.perform(get(path).cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void discoveryWritesOfMyResourcesAreNotStored() throws Exception {
        for (var request : List.of(put("/api/v1/me/likes/1"), delete("/api/v1/me/likes/1"),
                put("/api/v1/me/subscriptions/marco"), delete("/api/v1/me/subscriptions/marco"),
                post("/api/v1/me/notifications/3/read"), post("/api/v1/me/notifications/bulk"))) {
            mvc.perform(request.cookie(authCookies.user(7L)))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        }
    }

    @Test
    void viewerDependentPublicReadKeepsPrivateNoCache() throws Exception {
        mvc.perform(get("/api/v1/blogs/marco").cookie(authCookies.user(7L)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"));
        mvc.perform(get("/api/v1/blogs/marco"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, no-cache"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/marco/rss", "/marco/atom", "/sitemap.xml", "/sitemap/pages.xml"})
    void feedsAndSitemapsAreOpenAndRevalidated(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache"));
    }

    /** 003 T128: 릴리스 노트 확인 버전 저장과 003 관리자 API 응답(오류 404 포함)도 저장하지 않는다. */
    @Test
    void portalMemberAndAdminResponsesAreNotStored() throws Exception {
        when(roleLookup.isActiveAdmin(5L)).thenReturn(true);
        for (var request : List.of(post("/api/v1/me/release-notes/seen").cookie(authCookies.user(7L)),
                get("/api/v1/admin/topics").cookie(authCookies.user(5L)),
                put("/api/v1/admin/settings/portal.min-content-length").cookie(authCookies.user(5L)),
                post("/api/v1/admin/release-notes").cookie(authCookies.user(5L)))) {
            mvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        }
        mvc.perform(get("/api/v1/admin/topics").cookie(authCookies.user(7L)))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }
}
