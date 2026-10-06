package net.java21.blog.backend.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA Auditing: {@code BaseTimeEntity}의 created_at·updated_at을 채운다.
 * 시각은 서버 시간대와 상관없이 UTC {@link Clock} 빈({@code TimeConfig})의 {@link Instant}로 만든다.
 * 애플리케이션 클래스가 아닌 별도 설정에 둬서 {@code @WebMvcTest} 같은 슬라이스 테스트에 끼어들지 않게 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
