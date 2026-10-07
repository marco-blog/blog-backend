package net.java21.blog.backend.post.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.security.JwtProvider;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 보호 글 열람 쿠키(004 research B4). 이름 {@code post_unlock_{postId}}, 값은 001 {@link JwtProvider}의 키로 서명한 짧은 토큰
 * ({@code typ = post-unlock}, {@code pid}, {@code pwf}, 만료 {@code blog.posts.unlock-ttl} 30분). 새 비밀 키를 두지 않는다.
 * <p>{@code pwf}는 비밀번호 해시의 지문(SHA-256 앞 8바이트, 16진수)이라 글의 비밀번호를 바꾸면 이전 쿠키가 무효가 된다.
 * BCrypt 해시의 앞부분은 알고리즘·비용 표시라 글마다 거의 같으므로 해시 문자열 앞 8자 대신 해시의 다이제스트를 쓴다.
 */
@Component
public class PostUnlockCookies {

    static final String COOKIE_PREFIX = "post_unlock_";
    static final String TYPE = "post-unlock";
    static final String POST_ID = "pid";
    static final String FINGERPRINT = "pwf";

    private final JwtProvider jwtProvider;
    private final PostsProperties properties;

    public PostUnlockCookies(JwtProvider jwtProvider, PostsProperties properties) {
        this.jwtProvider = jwtProvider;
        this.properties = properties;
    }

    public static String cookieName(long postId) {
        return COOKIE_PREFIX + postId;
    }

    /** 맞는 비밀번호를 넣은 방문자에게 줄 쿠키({@code HttpOnly; Secure; SameSite=Lax; Path=/; Max-Age=1800}). */
    public ResponseCookie issue(Post post) {
        String token = jwtProvider.issueScoped(TYPE,
                Map.of(POST_ID, post.getId(), FINGERPRINT, fingerprint(post.getPasswordHash())),
                properties.unlockTtl());
        return ResponseCookie.from(cookieName(post.getId()), token)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.unlockTtl())
                .build();
    }

    /** 이 요청에 이 보호 글의 유효한 열람 쿠키가 있는지(서명·만료·글 번호·비밀번호 지문). */
    public boolean isUnlocked(Post post, HttpServletRequest request) {
        if (post.getPasswordHash() == null) {
            return false;
        }
        String token = cookie(request, cookieName(post.getId()));
        if (token == null) {
            return false;
        }
        return jwtProvider.verifyScoped(token, TYPE)
                .filter(claims -> claims.get(POST_ID) instanceof Number id && id.longValue() == post.getId())
                .filter(claims -> fingerprint(post.getPasswordHash()).equals(claims.get(FINGERPRINT)))
                .isPresent();
    }

    /** 요청 쿠키로 판단하는 {@link PostUnlockCheck}. */
    public PostUnlockCheck checker(HttpServletRequest request) {
        return post -> isUnlocked(post, request);
    }

    static String fingerprint(String passwordHash) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((passwordHash == null ? "" : passwordHash).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
