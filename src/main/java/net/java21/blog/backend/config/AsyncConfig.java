package net.java21.blog.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @Async}(메일 발송, research R23). 실행기는 Boot 기본 {@code applicationTaskExecutor}
 * ({@code spring.task.execution.*})를 이름으로 지정해 쓴다. 피드 수집 전용 풀과는 섞지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
public class AsyncConfig {

    /** {@code @Async(AsyncConfig.EXECUTOR)}. */
    public static final String EXECUTOR = "applicationTaskExecutor";
}
