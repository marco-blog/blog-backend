package net.java21.blog.backend.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 005 T005: blog.outbound.* 기본값과 prod에서 allow-private 기동 실패. */
class OutboundPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties(OutboundProperties.class)
    @Import(OutboundConfig.class)
    static class Config {
    }

    @Test
    void defaults() {
        runner.run(context -> {
            assertThat(context.getBean(OutboundProperties.class)).isEqualTo(OutboundProperties.defaults());
            assertThat(context.getBean(OutboundProperties.class).portSet()).isEqualTo(Set.of(80, 443, 8080, 8443));
            assertThat(context).hasSingleBean(OutboundUrlGuard.class);
        });
    }

    @Test
    void invalidValuesFail() {
        runner.withPropertyValues("blog.outbound.allowed-ports=0").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.outbound.allowed-ports=70000").run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("blog.outbound.allowed-ports=").run(c -> assertThat(c).hasFailed());
    }

    @Test
    void allowPrivateFailsInProdOnly() {
        runner.withPropertyValues("blog.outbound.allow-private=true").run(c -> assertThat(c).hasNotFailed());
        runner.withPropertyValues("spring.profiles.active=prod", "blog.outbound.allow-private=true")
                .run(c -> assertThat(c).hasFailed());
        runner.withPropertyValues("spring.profiles.active=prod").run(c -> assertThat(c).hasNotFailed());
    }
}
