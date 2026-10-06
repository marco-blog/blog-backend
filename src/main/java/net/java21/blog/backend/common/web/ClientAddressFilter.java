package net.java21.blog.backend.common.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 방문자 주소를 정한다({@code request.getRemoteAddr()}). 로그인 기록(FR-139) 등은 이 값만 쓴다.
 * <p>
 * 브라우저 요청은 front 서버(SSR·프록시)를 거쳐 오므로 접속 주소는 front 서버다. front는 방문자 주소를
 * {@code X-Forwarded-For} 끝에 붙여 보낸다. 이 헤더는 누구나 보낼 수 있으므로:
 * <ul>
 *   <li>접속 주소가 믿는 프록시({@link TrustedProxies})일 때만 헤더를 읽는다.</li>
 *   <li>오른쪽(가장 가까운 hop)부터 믿는 프록시를 건너뛰고 처음 만나는 주소를 방문자로 본다. 왼쪽 값은 방문자가 꾸민 것일 수 있다.</li>
 *   <li>IP 표기가 아닌 값을 만나면 거기서 멈추고 마지막으로 확인한 주소를 쓴다.</li>
 *   <li>{@code X-Forwarded-Proto}(http·https)도 믿는 프록시가 보낸 것만 {@code getScheme()}·{@code isSecure()}에 반영한다.</li>
 * </ul>
 * Spring의 {@code server.forward-headers-strategy=framework}는 모든 접속자의 헤더를 믿으므로 쓰지 않는다({@code none}).
 */
public class ClientAddressFilter extends OncePerRequestFilter {

    static final String FORWARDED_FOR = "X-Forwarded-For";
    static final String FORWARDED_PROTO = "X-Forwarded-Proto";

    private final TrustedProxies trustedProxies;

    public ClientAddressFilter(TrustedProxies trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String peer = request.getRemoteAddr();
        if (trustedProxies.isEmpty() || !trustedProxies.contains(peer)) {
            chain.doFilter(request, response);
            return;
        }
        String client = clientAddress(peer, forwardedFor(request));
        String scheme = forwardedScheme(request.getHeader(FORWARDED_PROTO));
        chain.doFilter(new ForwardedRequest(request, client, scheme), response);
    }

    /** 오른쪽부터 믿는 프록시를 건너뛰고 처음 만나는 주소. */
    private String clientAddress(String peer, List<String> forwardedFor) {
        String current = peer;
        for (int i = forwardedFor.size() - 1; i >= 0; i--) {
            String candidate = forwardedFor.get(i);
            if (!TrustedProxies.isIpLiteral(candidate)) {
                break;
            }
            current = candidate;
            if (!trustedProxies.contains(candidate)) {
                break;
            }
        }
        return current;
    }

    /** 여러 줄·쉼표로 나뉜 값을 왼쪽부터 순서대로. */
    private static List<String> forwardedFor(HttpServletRequest request) {
        List<String> values = new ArrayList<>();
        for (String header : Collections.list(request.getHeaders(FORWARDED_FOR))) {
            for (String part : header.split(",")) {
                String value = part.strip();
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    /** 방문자 쪽(맨 왼쪽) 값이 http·https일 때만. */
    private static String forwardedScheme(String header) {
        if (header == null) {
            return null;
        }
        String first = header.split(",")[0].strip().toLowerCase(Locale.ROOT);
        return first.equals("http") || first.equals("https") ? first : null;
    }

    private static final class ForwardedRequest extends HttpServletRequestWrapper {

        private final String client;
        private final String scheme;

        ForwardedRequest(HttpServletRequest request, String client, String scheme) {
            super(request);
            this.client = client;
            this.scheme = scheme;
        }

        @Override
        public String getRemoteAddr() {
            return client;
        }

        @Override
        public String getRemoteHost() {
            return client;
        }

        @Override
        public String getScheme() {
            return scheme != null ? scheme : super.getScheme();
        }

        @Override
        public boolean isSecure() {
            return scheme != null ? scheme.equals("https") : super.isSecure();
        }
    }
}
