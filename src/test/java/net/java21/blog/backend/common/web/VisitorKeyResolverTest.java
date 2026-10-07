package net.java21.blog.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import jakarta.servlet.http.Cookie;

import net.java21.blog.backend.post.PostsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 004 T010: 방문자 키는 회원 {@code u:{id}}, 아니면 방문자 쿠키 {@code v:{uuid}}이고 없거나 틀리면 새로 발급한다. */
class VisitorKeyResolverTest {

    private final VisitorKeyResolver resolver = new VisitorKeyResolver(
            new PostsProperties(Duration.ofMinutes(30), 10, "visitor_id", Duration.ofDays(365)));

    @Test
    void memberKeyWithoutCookie() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(resolver.resolve(7L, new MockHttpServletRequest(), response)).isEqualTo("u:7");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(resolver.peek(7L, new MockHttpServletRequest())).isEqualTo("u:7");
    }

    @Test
    void existingVisitorCookieIsReused() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("other", "x"), new Cookie("visitor_id", "3f1c2b8e-1111-2222-3333-444455556666"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(resolver.resolve(null, request, response)).isEqualTo("v:3f1c2b8e-1111-2222-3333-444455556666");
        assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        assertThat(resolver.peek(null, request)).isEqualTo("v:3f1c2b8e-1111-2222-3333-444455556666");
    }

    @Test
    void missingOrMalformedCookieIssuesNewOne() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("visitor_id", "<bad>"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        String key = resolver.resolve(null, request, response);

        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).startsWith("visitor_id=").contains("Path=/", "HttpOnly", "Secure", "SameSite=Lax",
                "Max-Age=31536000");
        assertThat(key).isEqualTo("v:" + setCookie.substring("visitor_id=".length(), setCookie.indexOf(';')));
        assertThat(resolver.peek(null, request)).isNull();
        assertThat(resolver.peek(null, new MockHttpServletRequest())).isNull();
    }
}
