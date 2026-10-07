package net.java21.blog.backend.admin.dashboard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 처리 대기 신고 수의 기본 구현: 005의 구현 빈이 없으면 {@link NoPendingReportCounter}(006 T031). */
@Configuration(proxyBeanMethods = false)
public class AdminDashboardConfig {

    @Bean
    @ConditionalOnMissingBean(PendingReportCounter.class)
    PendingReportCounter noPendingReportCounter() {
        return new NoPendingReportCounter();
    }
}
