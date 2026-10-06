package net.java21.blog.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 방문자 IP(로그인 기록 등)는 믿는 프록시(front 서버)가 보낸 {@code X-Forwarded-For}에서만 꺼낸다.
 * 오른쪽(가장 가까운 hop)부터 믿는 프록시를 건너뛰고 처음 만나는 주소가 방문자다. 왼쪽 값은 방문자가 꾸밀 수 있다.
 */
class ClientAddressFilterTest {

    private static final List<String> LOCAL = List.of("127.0.0.1", "::1");

    private static HttpServletRequest filter(List<String> trusted, MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new ClientAddressFilter(TrustedProxies.of(trusted)).doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    private static MockHttpServletRequest from(String remoteAddr, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        request.setRemoteAddr(remoteAddr);
        for (String value : forwardedFor) {
            request.addHeader("X-Forwarded-For", value);
        }
        return request;
    }

    @Test
    void trustedProxyForwardsVisitorAddress() throws Exception {
        HttpServletRequest out = filter(LOCAL, from("127.0.0.1", "203.0.113.7"));

        assertThat(out.getRemoteAddr()).isEqualTo("203.0.113.7");
        assertThat(out.getRemoteHost()).isEqualTo("203.0.113.7");
    }

    @Test
    void forgedLeftValuesAreIgnored() throws Exception {
        // 방문자가 X-Forwarded-For: 1.1.1.1을 보내도 front가 실제 접속 주소를 뒤에 붙인다.
        HttpServletRequest out = filter(LOCAL, from("127.0.0.1", "1.1.1.1, 198.51.100.9"));

        assertThat(out.getRemoteAddr()).isEqualTo("198.51.100.9");
    }

    @Test
    void chainOfTrustedProxiesIsSkipped() throws Exception {
        HttpServletRequest out = filter(List.of("127.0.0.1", "10.0.0.0/8"),
                from("10.0.0.6", "203.0.113.7, 127.0.0.1", "10.0.0.5"));

        assertThat(out.getRemoteAddr()).isEqualTo("203.0.113.7");
    }

    @Test
    void untrustedPeerCannotSetAddress() throws Exception {
        HttpServletRequest out = filter(LOCAL, from("198.51.100.9", "1.1.1.1"));

        assertThat(out.getRemoteAddr()).isEqualTo("198.51.100.9");
    }

    @Test
    void noTrustedProxiesMeansHeadersAreIgnored() throws Exception {
        HttpServletRequest out = filter(List.of(), from("127.0.0.1", "203.0.113.7"));

        assertThat(out.getRemoteAddr()).isEqualTo("127.0.0.1");
    }

    @Test
    void withoutHeaderThePeerIsTheVisitor() throws Exception {
        assertThat(filter(LOCAL, from("127.0.0.1")).getRemoteAddr()).isEqualTo("127.0.0.1");
    }

    @Test
    void ipv6LoopbackProxy() throws Exception {
        assertThat(filter(LOCAL, from("0:0:0:0:0:0:0:1", "2001:db8::7")).getRemoteAddr()).isEqualTo("2001:db8::7");
    }

    @Test
    void garbageEntryStopsTheWalk() throws Exception {
        // 형식이 틀린 값 앞(왼쪽)은 믿을 수 없다. 마지막으로 확인한 주소를 쓴다.
        HttpServletRequest out = filter(LOCAL, from("127.0.0.1", "203.0.113.7, unknown, ::1"));

        assertThat(out.getRemoteAddr()).isEqualTo("::1");
    }

    @Test
    void allTrustedUsesLeftmost() throws Exception {
        assertThat(filter(LOCAL, from("127.0.0.1", "::1, 127.0.0.1")).getRemoteAddr()).isEqualTo("::1");
    }

    @Test
    void forwardedProtoFromTrustedProxy() throws Exception {
        MockHttpServletRequest request = from("127.0.0.1", "203.0.113.7");
        request.addHeader("X-Forwarded-Proto", "https");

        HttpServletRequest out = filter(LOCAL, request);

        assertThat(out.getScheme()).isEqualTo("https");
        assertThat(out.isSecure()).isTrue();
    }

    @Test
    void forwardedProtoUsesTheVisitorSideValue() throws Exception {
        MockHttpServletRequest request = from("127.0.0.1", "203.0.113.7");
        request.addHeader("X-Forwarded-Proto", "HTTPS, http");

        assertThat(filter(LOCAL, request).isSecure()).isTrue();
    }

    @Test
    void forwardedProtoIgnoredFromUntrustedPeerOrWhenInvalid() throws Exception {
        MockHttpServletRequest untrusted = from("198.51.100.9");
        untrusted.addHeader("X-Forwarded-Proto", "https");
        assertThat(filter(LOCAL, untrusted).isSecure()).isFalse();

        MockHttpServletRequest invalid = from("127.0.0.1");
        invalid.addHeader("X-Forwarded-Proto", "gopher");
        HttpServletRequest out = filter(LOCAL, invalid);
        assertThat(out.getScheme()).isEqualTo("http");
        assertThat(out.isSecure()).isFalse();
    }

    @Test
    void registeredRightAfterRequestIdAndBeforeSecurity() {
        var registration = new ClientAddressConfig().clientAddressFilter(
                new ClientAddressConfig.TrustedProxyProperties(List.of("127.0.0.1")));

        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(registration.getFilter()).isInstanceOf(ClientAddressFilter.class);
    }
}
