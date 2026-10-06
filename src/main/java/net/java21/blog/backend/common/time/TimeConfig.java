package net.java21.blog.backend.common.time;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 애플리케이션 전체가 쓰는 UTC {@link Clock}. 현재 시각이 필요한 코드는 {@code Instant.now()} 대신 이 빈을 주입받는다
 * (JPA Auditing, 토큰 만료, 정기 작업). 테스트는 고정 Clock으로 바꿔 시간을 조절한다.
 */
@Configuration(proxyBeanMethods = false)
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
