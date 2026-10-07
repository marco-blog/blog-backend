package net.java21.blog.backend.config;

import net.java21.blog.backend.common.net.OutboundUrlGuard;
import net.java21.blog.backend.common.net.SafeHttpFetcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 외부 요청 도구 빈(007 research E2). 피드·블로그 HTML·대표 이미지·원문 링크 점검이 모두 이 하나를 쓴다. */
@Configuration(proxyBeanMethods = false)
public class ExternalFetchConfig {

    @Bean
    SafeHttpFetcher safeHttpFetcher(OutboundUrlGuard guard, ExternalFeedProperties properties, SiteProperties site) {
        return new SafeHttpFetcher(guard, settings(properties, site.baseUrl()));
    }

    /** 프로퍼티 → 요청 설정. */
    public static SafeHttpFetcher.Settings settings(ExternalFeedProperties properties, String baseUrl) {
        return new SafeHttpFetcher.Settings(properties.connectTimeout(), properties.requestTimeout(),
                properties.maxRedirects(), properties.maxFeedSize().toBytes(), properties.maxPageSize().toBytes(),
                properties.maxImageSize().toBytes(), properties.selfHosts(baseUrl), properties.userAgent(baseUrl));
    }
}
