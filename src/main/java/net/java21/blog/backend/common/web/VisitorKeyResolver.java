package net.java21.blog.backend.common.web;

import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import net.java21.blog.backend.post.PostsProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 방문자 키(001 research R19, 004 research B9): 조회수·끝까지 읽음·블로그 방문·비밀번호 시도 제한이 같은 키를 쓴다.
 * 회원이면 {@code u:{id}}, 아니면 익명 방문자 쿠키 {@code v:{uuid}}이고, 쿠키가 없거나 형식이 틀리면 새 UUID를 만들어
 * {@code Set-Cookie}(HttpOnly, Secure, SameSite=Lax, Path=/, Max-Age={@code blog.posts.visitor-cookie-max-age})로 내려준다
 * (front가 브라우저에 전달).
 */
@Component
public class VisitorKeyResolver {

    /** 방문자 쿠키 값: UUID 등 짧은 토큰만 받는다. */
    private static final Pattern VISITOR_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final PostsProperties properties;

    public VisitorKeyResolver(PostsProperties properties) {
        this.properties = properties;
    }

    /** 이 요청의 방문자 키. 필요하면 방문자 쿠키를 발급한다. */
    public String resolve(Long viewerId, HttpServletRequest request, HttpServletResponse response) {
        if (viewerId != null) {
            return "u:" + viewerId;
        }
        String visitorId = existing(request);
        if (visitorId == null) {
            visitorId = UUID.randomUUID().toString();
            ResponseCookie cookie = ResponseCookie.from(properties.visitorCookie(), visitorId)
                    .path("/")
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("Lax")
                    .maxAge(properties.visitorCookieMaxAge())
                    .build();
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return "v:" + visitorId;
    }

    /** 쿠키를 발급하지 않고 지금 요청의 방문자 키만 본다(회원 {@code u:}, 쿠키 {@code v:}, 둘 다 없으면 null). */
    public String peek(Long viewerId, HttpServletRequest request) {
        if (viewerId != null) {
            return "u:" + viewerId;
        }
        String visitorId = existing(request);
        return visitorId == null ? null : "v:" + visitorId;
    }

    /** 요청의 방문자 쿠키 값. 없거나 형식이 맞지 않으면 null. */
    private String existing(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (properties.visitorCookie().equals(cookie.getName())
                        && VISITOR_ID.matcher(cookie.getValue()).matches()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
