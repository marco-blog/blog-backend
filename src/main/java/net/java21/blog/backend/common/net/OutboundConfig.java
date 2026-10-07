package net.java21.blog.backend.common.net;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** 나가는 요청 검사 빈(005 research M16). 운영 프로필(prod)에서 {@code blog.outbound.allow-private=true}면 기동을 멈춘다. */
@Configuration(proxyBeanMethods = false)
public class OutboundConfig {

    @Bean
    HostResolver hostResolver() {
        return HostResolver.system();
    }

    @Bean
    OutboundUrlGuard outboundUrlGuard(OutboundProperties properties, HostResolver hostResolver,
            Environment environment) {
        validate(properties, environment.acceptsProfiles(Profiles.of("prod")));
        return new OutboundUrlGuard(properties, hostResolver);
    }

    static void validate(OutboundProperties properties, boolean production) {
        if (production && properties.allowPrivate()) {
            throw new IllegalStateException("blog.outbound.allow-private must be false in the prod profile");
        }
    }
}
