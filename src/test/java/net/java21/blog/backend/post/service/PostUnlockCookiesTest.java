package net.java21.blog.backend.post.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.security.JwtProvider;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;
import org.springframework.mock.web.MockHttpServletRequest;

/** 보호 글 열람 쿠키(T076, research B4): 서명·만료·글 번호·비밀번호 지문을 모두 맞아야 연 것으로 본다. */
class PostUnlockCookiesTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final String HASH = "$2a$04$ZYzMyrGQlHSVU2Iu3lk5eeGJTrcgI6079NcWo99ZLWs5DfNyLYOim";

    private final MutableClock clock = new MutableClock(NOW);
    private JwtProvider jwt;
    private PostUnlockCookies cookies;
    private Post post;

    @BeforeEach
    void setUp() {
        jwt = new JwtProvider(new AuthProperties(Duration.ofMinutes(30), Duration.ofHours(4), Duration.ofDays(7),
                Duration.ofSeconds(10), "unit-test-only-jwt-secret-0123456789abcdef", 5, Duration.ofMinutes(10)),
                clock);
        cookies = new PostUnlockCookies(jwt, PostsProperties.defaults());
        post = protectedPost(100L, HASH);
    }

    @Test
    void issuedCookieHasContractAttributes() {
        ResponseCookie cookie = cookies.issue(post);

        assertThat(cookie.getName()).isEqualTo("post_unlock_100");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofMinutes(30));
        assertThat(cookie.getValue()).doesNotContain(HASH);
    }

    @Test
    void validCookieUnlocksUntilItExpires() {
        MockHttpServletRequest request = requestWith(cookies.issue(post));

        assertThat(cookies.isUnlocked(post, request)).isTrue();
        assertThat(cookies.checker(request).isUnlocked(post)).isTrue();

        clock.advance(Duration.ofMinutes(30).plusSeconds(1));
        assertThat(cookies.isUnlocked(post, request)).isFalse();
    }

    @Test
    void changingThePasswordInvalidatesTheCookie() {
        MockHttpServletRequest request = requestWith(cookies.issue(post));
        post.applyProtection("$2a$04$differentdifferentdifferentdifferentdifferentdiffere");

        assertThat(cookies.isUnlocked(post, request)).isFalse();
    }

    @Test
    void cookieOfAnotherPostDoesNotUnlock() {
        Post other = protectedPost(200L, HASH);
        ResponseCookie forOther = cookies.issue(other);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("post_unlock_100", forOther.getValue()));

        assertThat(cookies.isUnlocked(post, request)).as("쿠키 이름만 바꿔도 글 번호가 다르다").isFalse();
    }

    @Test
    void missingTamperedOrForeignTokensDoNotUnlock() {
        assertThat(cookies.isUnlocked(post, new MockHttpServletRequest())).isFalse();

        MockHttpServletRequest tampered = new MockHttpServletRequest();
        tampered.setCookies(new Cookie("other", "x"), new Cookie("post_unlock_100", "a.b.c"));
        assertThat(cookies.isUnlocked(post, tampered)).isFalse();

        MockHttpServletRequest access = new MockHttpServletRequest();
        access.setCookies(new Cookie("post_unlock_100", jwt.issue(1L, "USER", "family")));
        assertThat(cookies.isUnlocked(post, access)).as("접근 토큰은 열람 쿠키가 아니다").isFalse();

        MockHttpServletRequest wrongType = new MockHttpServletRequest();
        wrongType.setCookies(new Cookie("post_unlock_100", jwt.issueScoped("other",
                Map.of("pid", 100L, "pwf", PostUnlockCookies.fingerprint(HASH)), Duration.ofMinutes(5))));
        assertThat(cookies.isUnlocked(post, wrongType)).isFalse();
    }

    @Test
    void postWithoutPasswordIsNeverUnlocked() {
        MockHttpServletRequest request = requestWith(cookies.issue(post));
        Post plain = TestEntities.post(100L, post.getBlog(), "t");

        assertThat(cookies.isUnlocked(plain, request)).isFalse();
    }

    @Test
    void fingerprintIsShortAndDependsOnTheHash() {
        assertThat(PostUnlockCookies.fingerprint(HASH)).hasSize(16).isNotEqualTo(PostUnlockCookies.fingerprint("x"));
        assertThat(PostUnlockCookies.fingerprint(null)).hasSize(16);
        assertThat(PostUnlockCookies.cookieName(7L)).isEqualTo("post_unlock_7");
    }

    private static Post protectedPost(long id, String hash) {
        Post p = TestEntities.post(id, TestEntities.blog(10L, TestEntities.user(1L), "marco"), "t");
        p.publish("t", "b", "<p>b</p>", "b", "b", null, PostVisibility.PROTECTED, true, NOW);
        p.applyProtection(hash);
        return p;
    }

    private static MockHttpServletRequest requestWith(ResponseCookie cookie) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(cookie.getName(), cookie.getValue()));
        return request;
    }
}
