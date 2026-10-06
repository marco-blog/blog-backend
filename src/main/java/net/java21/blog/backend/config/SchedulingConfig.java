package net.java21.blog.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 정기 작업(001 research R26, 007 research E1). 스케줄러 풀 크기는 {@code spring.task.scheduling.pool.size}(3).
 * 스케줄러 작업은 "고르고 넘기기"만 하고, 피드 수집은 전용 풀 {@code feedFetchExecutor}에서 한다.
 * 이 Executor 빈이 있어도 Boot 기본 {@code applicationTaskExecutor}가 만들어지도록
 * {@code spring.task.execution.mode=force}로 둔다(다른 비동기 작업과 풀을 공유하지 않음).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ExternalFeedProperties.class)
public class SchedulingConfig {

    static final int SHUTDOWN_AWAIT_SECONDS = 30;

    /** 큐가 차면 {@code TaskRejectedException}: 호출한 스케줄러는 이번 차례를 건너뛰고 다음 분에 다시 고른다. */
    @Bean
    ThreadPoolTaskExecutor feedFetchExecutor(ExternalFeedProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.fetchThreads());
        executor.setMaxPoolSize(properties.fetchThreads());
        executor.setQueueCapacity(properties.batchSize());
        executor.setThreadNamePrefix("feed-fetch-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        return executor;
    }
}
