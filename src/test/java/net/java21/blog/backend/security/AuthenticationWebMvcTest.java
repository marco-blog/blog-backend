package net.java21.blog.backend.security;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;

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

/** {@code access_token} 쿠키 인증, 공개 경로, {@link CurrentUser} 주입(T028). */
@WebMvcTest(controllers = AuthenticationTestController.class)
@Import({WebMvcTestSupport.class, AuthenticationTestController.class})
class AuthenticationWebMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthCookies authCookies;

    @Autowired
    private JwtProvider jwtProvider;

    @Test
    void accessTokenCookieAuthenticatesAndInjectsCurrentUser() throws Exception {
        mvc.perform(get("/api/v1/me").cookie(authCookies.of(7L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.result.userId").value(7))
                .andExpect(jsonPath("$.result.role").value("ADMIN"))
                .andExpect(jsonPath("$.result.familyId").value(AuthCookies.FAMILY_ID));
    }

    @Test
    void stateChangingRequestWithCookie() throws Exception {
        mvc.perform(post("/api/v1/blogs/marco/posts").cookie(authCookies.user(3L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value(3));
    }

    @Test
    void missingCookieIs401InCommonFormat() throws Exception {
        expectUnauthenticated(mvc.perform(get("/api/v1/me")));
        expectUnauthenticated(mvc.perform(post("/api/v1/blogs/marco/posts")));
    }

    @Test
    void expiredCookieIs401() throws Exception {
        expectUnauthenticated(mvc.perform(get("/api/v1/me").cookie(authCookies.expired(7L))));
    }

    @Test
    void invalidCookieIs401() throws Exception {
        expectUnauthenticated(mvc.perform(get("/api/v1/me").cookie(AuthCookies.cookie("garbage"))));
        expectUnauthenticated(mvc.perform(get("/api/v1/me").cookie(AuthCookies.cookie(""))));
    }

    @Test
    void authorizationHeaderIsIgnored() throws Exception {
        String token = jwtProvider.issue(7L, "USER", AuthCookies.FAMILY_ID);
        expectUnauthenticated(mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)));
    }

    @Test
    void otherCookiesAreIgnored() throws Exception {
        String token = jwtProvider.issue(7L, "USER", AuthCookies.FAMILY_ID);
        expectUnauthenticated(mvc.perform(get("/api/v1/me").cookie(new Cookie("refresh_token", token))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/blogs/marco", "/api/v1/posts/123", "/api/v1/tags/java", "/api/v1/legal/terms",
            "/media/abcDEF123", "/v3/api-docs", "/actuator/health"})
    void publicGetAllowsAnonymous(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true));
    }

    @Test
    void publicGetSeesCurrentUserWhenLoggedInAndNullOtherwise() throws Exception {
        mvc.perform(get("/api/v1/blogs/marco"))
                .andExpect(jsonPath("$.result.viewer").value("anonymous"));
        mvc.perform(get("/api/v1/blogs/marco").cookie(authCookies.user(9L)))
                .andExpect(jsonPath("$.result.viewer").value(9));
        mvc.perform(get("/api/v1/blogs/marco").cookie(authCookies.expired(9L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.viewer").value("anonymous"));
    }

    @Test
    void onlySinglePostDetailIsPublic() throws Exception {
        expectUnauthenticated(mvc.perform(get("/api/v1/posts/123/draft")));
        mvc.perform(get("/api/v1/posts/123/draft").cookie(authCookies.user(1L))).andExpect(status().isOk());
    }

    @Test
    void publicPathsAreGetOnly() throws Exception {
        expectUnauthenticated(mvc.perform(post("/api/v1/blogs/marco/posts")));
    }

    @Test
    void requiredCurrentUserOnPublicPathIs401() throws Exception {
        expectUnauthenticated(mvc.perform(get("/api/v1/tags/java/mine")));
        mvc.perform(get("/api/v1/tags/java/mine").cookie(authCookies.user(5L)))
                .andExpect(jsonPath("$.result").value(5));
    }

    private static void expectUnauthenticated(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.resultCode").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.header.traceId").exists())
                .andExpect(jsonPath("$.result").value(nullValue()));
    }
}
