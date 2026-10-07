package net.java21.blog.backend.common.net;

import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 서버가 밖으로 보내는 요청의 허용 범위(005 research M16, {@code blog.outbound.*}). 트랙백 송신(005)과 외부 피드 수집(007)이 함께 쓴다.
 * {@code allow-private}는 시험용이며 운영 프로필(prod)에서 true면 {@link OutboundConfig}가 기동을 멈춘다.
 *
 * @param allowedPorts 허용 포트
 * @param allowPrivate 내부망·루프백 주소 허용(시험용)
 */
@ConfigurationProperties("blog.outbound")
public record OutboundProperties(
        @DefaultValue({"80", "443", "8080", "8443"}) List<Integer> allowedPorts,
        @DefaultValue("false") boolean allowPrivate) {

    public OutboundProperties {
        if (allowedPorts == null || allowedPorts.isEmpty()) {
            throw new IllegalArgumentException("blog.outbound.allowed-ports must not be empty");
        }
        for (Integer port : allowedPorts) {
            if (port == null || port < 1 || port > 65535) {
                throw new IllegalArgumentException("blog.outbound.allowed-ports must be 1..65535: " + port);
            }
        }
        allowedPorts = List.copyOf(allowedPorts);
    }

    public Set<Integer> portSet() {
        return Set.copyOf(allowedPorts);
    }

    /** 기본값(테스트용). */
    public static OutboundProperties defaults() {
        return new OutboundProperties(List.of(80, 443, 8080, 8443), false);
    }
}
