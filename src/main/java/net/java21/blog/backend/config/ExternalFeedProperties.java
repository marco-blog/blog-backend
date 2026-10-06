package net.java21.blog.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 외부 블로그 피드 수집 설정(007 research E1). 지금은 수집 전용 스레드 풀 크기만 쓴다.
 *
 * @param fetchThreads 동시에 받는 피드 수(수집 풀 core=max)
 * @param batchSize    한 차례에 고르는 피드 수(수집 풀 대기열 크기)
 */
@ConfigurationProperties("blog.external")
public record ExternalFeedProperties(
        @DefaultValue("4") int fetchThreads,
        @DefaultValue("50") int batchSize) {
}
