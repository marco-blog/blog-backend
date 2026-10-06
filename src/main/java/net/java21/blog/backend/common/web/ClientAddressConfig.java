package net.java21.blog.backend.common.web;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 방문자 주소 필터({@link ClientAddressFilter}) 등록. 추적 ID 필터 바로 다음, 보안 필터보다 먼저 돈다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClientAddressConfig.TrustedProxyProperties.class)
public class ClientAddressConfig {

    @Bean
    FilterRegistrationBean<ClientAddressFilter> clientAddressFilter(TrustedProxyProperties properties) {
        FilterRegistrationBean<ClientAddressFilter> registration =
                new FilterRegistrationBean<>(new ClientAddressFilter(TrustedProxies.of(properties.trustedProxies())));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /**
     * @param trustedProxies {@code X-Forwarded-For}·{@code X-Forwarded-Proto}를 믿는 접속 주소(IP 또는 CIDR).
     *                       front 서버의 주소를 넣는다. 비우면 아무도 믿지 않는다(접속 주소가 곧 방문자)
     */
    @ConfigurationProperties("blog.security")
    public record TrustedProxyProperties(List<String> trustedProxies) {
    }
}
