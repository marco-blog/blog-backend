package net.java21.blog.backend.trackback;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 트랙백 보내기 실행기 빈 {@code trackbackExecutor}(005 research M15). */
@Configuration(proxyBeanMethods = false)
public class TrackbackExecutorConfig {

    public static final String EXECUTOR = "trackbackExecutor";

    @Bean(EXECUTOR)
    TrackbackExecutor trackbackExecutor(TrackbackProperties properties) {
        return new TrackbackExecutor(properties.executorThreads(), properties.executorQueue());
    }
}
