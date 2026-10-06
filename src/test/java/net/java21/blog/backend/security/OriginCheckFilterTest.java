package net.java21.blog.backend.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import net.java21.blog.backend.common.error.ApiErrorWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class OriginCheckFilterTest {

    private static final String FRONT = "https://blog.java21.net";

    private final OriginCheckFilter filter = new OriginCheckFilter(
            List.of(FRONT, "http://localhost:5173/"), new ApiErrorWriter(JsonMapper.builder().build()));

    private MockHttpServletResponse run(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    private static MockHttpServletRequest request(String method, String uri, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return request;
    }

    private static void assertBlocked(MockHttpServletResponse response, MockFilterChain chain) throws Exception {
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString())
                .contains("\"resultCode\":\"ORIGIN_NOT_ALLOWED\"")
                .contains("\"isSuccessful\":false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void allowedOriginPasses(String method) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = run(request(method, "/api/v1/posts", FRONT), chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void originIsComparedWithoutCaseOrTrailingSlash() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        run(request("POST", "/api/v1/posts", "http://LOCALHOST:5173"), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void otherOriginIsBlocked(String method) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertBlocked(run(request(method, "/api/v1/posts", "https://evil.example"), chain), chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "https://blog.java21.net.evil.example", "http://blog.java21.net", ""})
    void lookalikeOriginsAreBlocked(String origin) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertBlocked(run(request("POST", "/api/v1/posts", origin), chain), chain);
    }

    @Test
    void missingOriginIsBlockedEvenWithReferer() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/v1/posts", null);
        request.addHeader("Referer", FRONT + "/write");
        MockFilterChain chain = new MockFilterChain();
        assertBlocked(run(request, chain), chain);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void safeMethodsAreNotChecked(String method) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        run(request(method, "/api/v1/posts", "https://evil.example"), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void trackbackPingIsExempt() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        run(request("POST", "/marco/42/trackback", null), chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void trackbackExemptionHonoursContextPath() throws Exception {
        MockHttpServletRequest request = request("POST", "/app/marco/42/trackback", null);
        request.setContextPath("/app");
        MockFilterChain chain = new MockFilterChain();
        run(request, chain);
        assertThat(chain.getRequest()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/marco/abc/trackback", "/api/v1/marco/42/trackback", "/marco/42/trackback/x"})
    void onlyTheExactTrackbackPathIsExempt(String uri) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertBlocked(run(request("POST", uri, null), chain), chain);
    }

    @Test
    void emptyAllowListFailsAtStartup() {
        ApiErrorWriter writer = new ApiErrorWriter(JsonMapper.builder().build());
        assertThatThrownBy(() -> new OriginCheckFilter(List.of(), writer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blog.security.allowed-origins");
        assertThatThrownBy(() -> new OriginCheckFilter(null, writer)).isInstanceOf(IllegalStateException.class);
    }
}
