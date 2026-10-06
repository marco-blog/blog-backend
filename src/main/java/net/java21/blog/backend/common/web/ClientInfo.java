package net.java21.blog.backend.common.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;

/**
 * 요청한 방문자의 주소와 기기 정보(로그인 기록, FR-139). 주소는 {@link ClientAddressFilter}가 정한 {@code getRemoteAddr()}만 쓴다.
 *
 * @param ip        방문자 IP. 개인정보이므로 저장할 때 암호화한다(FR-134)
 * @param userAgent {@code User-Agent} 헤더 원문(없으면 null)
 */
public record ClientInfo(String ip, String userAgent) {

    public static ClientInfo of(HttpServletRequest request) {
        return new ClientInfo(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }

    /** 로그에 IP가 찍히지 않게 가린다. */
    @Override
    public String toString() {
        return "ClientInfo[ip=****, userAgent=" + userAgent + "]";
    }
}
