package net.java21.blog.backend.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 005 T010: SSRF 방어(research M16). 이름 해석은 가짜 {@link HostResolver}. */
class OutboundUrlGuardTest {

    private final Map<String, List<String>> dns = new HashMap<>();
    private final HostResolver resolver = host -> {
        List<String> addresses = dns.get(host);
        if (addresses == null) {
            return List.of(InetAddress.getByName(host)); // 숫자 표기만 여기로 온다
        }
        List<InetAddress> result = new ArrayList<>();
        for (String address : addresses) {
            result.add(InetAddress.getByName(address));
        }
        return result;
    };
    private final OutboundUrlGuard guard = new OutboundUrlGuard(OutboundProperties.defaults(), resolver);

    private OutboundBlockedException.Reason reason(String url) {
        try {
            guard.check(URI.create(url));
        } catch (OutboundBlockedException e) {
            return e.reason();
        }
        return null;
    }

    @Test
    void publicAddressPasses() {
        dns.put("example.com", List.of("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"));
        OutboundUrlGuard.Target target = guard.check(URI.create("https://Example.com/tb"));
        assertThat(target.host()).isEqualTo("example.com");
        assertThat(target.port()).isEqualTo(443);
        assertThat(target.addresses()).hasSize(2);
        assertThat(guard.check(URI.create("http://example.com:8080/x")).port()).isEqualTo(8080);
        assertThat(guard.check(URI.create("http://93.184.216.34/")).port()).isEqualTo(80);
    }

    @Test
    void onlyHttpAndAllowedPorts() {
        dns.put("example.com", List.of("93.184.216.34"));
        assertThat(reason("ftp://example.com/")).isEqualTo(OutboundBlockedException.Reason.INVALID_URL);
        assertThat(reason("file:///etc/passwd")).isEqualTo(OutboundBlockedException.Reason.INVALID_URL);
        assertThat(reason("/relative")).isEqualTo(OutboundBlockedException.Reason.INVALID_URL);
        assertThat(reason("http://user@example.com/")).isEqualTo(OutboundBlockedException.Reason.INVALID_URL);
        assertThat(reason("http://example.com:22/")).isEqualTo(OutboundBlockedException.Reason.PORT_NOT_ALLOWED);
        assertThat(reason("http:///nohost")).isEqualTo(OutboundBlockedException.Reason.INVALID_URL);
        assertThatThrownBy(() -> guard.check(null)).isInstanceOf(OutboundBlockedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://127.0.0.1/", "http://[::1]/", "http://10.1.2.3/", "http://172.16.0.1/",
            "http://192.168.1.1/", "http://169.254.169.254/latest", "http://[fe80::1]/", "http://[fc00::1]/",
            "http://[fd12::1]/", "http://100.64.0.1/", "http://224.0.0.1/", "http://0.0.0.0/",
            "http://[::ffff:127.0.0.1]/", "http://[::ffff:a9fe:a9fe]/", "http://255.255.255.255/",
            "http://[2002:7f00:1::]/", "http://[64:ff9b::7f00:1]/"})
    void internalAddressesAreBlocked(String url) {
        assertThat(reason(url)).isEqualTo(OutboundBlockedException.Reason.BLOCKED_ADDRESS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://2130706433/", "http://0x7f000001/", "http://0177.0.0.1/", "http://127.1/",
            "http://0x7f.0.0.1/"})
    void numericHostNotationsAreRejected(String url) {
        assertThat(reason(url)).isEqualTo(OutboundBlockedException.Reason.INVALID_URL);
    }

    @Test
    void localhostNameAndMixedResolutionAreBlocked() {
        dns.put("localhost", List.of("127.0.0.1"));
        dns.put("mixed.example", List.of("93.184.216.34", "10.0.0.5"));
        assertThat(reason("http://localhost/")).isEqualTo(OutboundBlockedException.Reason.BLOCKED_ADDRESS);
        assertThat(reason("http://mixed.example/")).isEqualTo(OutboundBlockedException.Reason.BLOCKED_ADDRESS);
    }

    @Test
    void unresolvableHostIsRejected() {
        OutboundUrlGuard failing = new OutboundUrlGuard(OutboundProperties.defaults(), host -> {
            throw new UnknownHostException(host);
        });
        assertThatThrownBy(() -> failing.check(URI.create("http://nowhere.example/")))
                .isInstanceOfSatisfying(OutboundBlockedException.class,
                        e -> assertThat(e.reason()).isEqualTo(OutboundBlockedException.Reason.UNRESOLVABLE));
        OutboundUrlGuard empty = new OutboundUrlGuard(OutboundProperties.defaults(), host -> List.of());
        assertThatThrownBy(() -> empty.check(URI.create("http://nowhere.example/")))
                .isInstanceOf(OutboundBlockedException.class);
    }

    @Test
    void allowPrivatePassesInternalAddresses() {
        OutboundUrlGuard open = new OutboundUrlGuard(new OutboundProperties(List.of(80, 9999), true), resolver);
        assertThat(open.check(URI.create("http://127.0.0.1:9999/tb")).port()).isEqualTo(9999);
        assertThatThrownBy(() -> open.check(URI.create("http://127.0.0.1:22/")))
                .isInstanceOf(OutboundBlockedException.class);
    }

    @Test
    void systemResolverResolvesLiterals() throws Exception {
        assertThat(HostResolver.system().resolve("127.0.0.1")).singleElement()
                .satisfies(a -> assertThat(a.isLoopbackAddress()).isTrue());
    }
}
