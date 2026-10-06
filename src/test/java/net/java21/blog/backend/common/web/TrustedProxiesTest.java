package net.java21.blog.backend.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 믿는 프록시 목록(IP 또는 CIDR, IPv4·IPv6). 주소 문자열은 IP 표기만 받고 이름 조회(DNS)는 하지 않는다. */
class TrustedProxiesTest {

    @Test
    void exactAddressesAndCidrs() {
        TrustedProxies proxies = TrustedProxies.of(List.of("127.0.0.1", "::1", "10.0.0.0/8", "fd00::/8"));

        assertThat(proxies.contains("127.0.0.1")).isTrue();
        assertThat(proxies.contains("127.0.0.2")).isFalse();
        assertThat(proxies.contains("::1")).isTrue();
        assertThat(proxies.contains("0:0:0:0:0:0:0:1")).isTrue();
        assertThat(proxies.contains("10.20.30.40")).isTrue();
        assertThat(proxies.contains("11.0.0.1")).isFalse();
        assertThat(proxies.contains("fd12:3456::1")).isTrue();
        assertThat(proxies.contains("fe80::1")).isFalse();
    }

    @Test
    void ipv4MappedIpv6IsTheSameAddress() {
        assertThat(TrustedProxies.of(List.of("127.0.0.1")).contains("::ffff:127.0.0.1")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "unknown", "example.com", "1.2.3", "999.1.1.1", "1.2.3.4.5", "_hidden", "::g"})
    void nonIpStringsAreNeverTrustedOrResolved(String value) {
        assertThat(TrustedProxies.isIpLiteral(value)).isFalse();
        assertThat(TrustedProxies.of(List.of("0.0.0.0/0")).contains(value)).isFalse();
    }

    @Test
    void emptyListTrustsNobody() {
        assertThat(TrustedProxies.of(List.of()).isEmpty()).isTrue();
        assertThat(TrustedProxies.of(null).contains("127.0.0.1")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "10.0.0.0/33", "::1/129", "10.0.0.0/x", "10.0.0.0/"})
    void invalidConfigurationFailsFast(String entry) {
        assertThatThrownBy(() -> TrustedProxies.of(List.of(entry)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blog.security.trusted-proxies");
    }
}
