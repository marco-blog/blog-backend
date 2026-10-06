package net.java21.blog.backend.notification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 알림 설정(002 contracts/api.md "프로퍼티", research D3).
 *
 * @param retention              보관 기간. 지나면 {@code NotificationPurgeJob}이 지운다
 * @param subscriberDedupWindow  같은 구독자·블로그의 NEW_SUBSCRIBER를 다시 만들지 않는 기간(구독·취소 반복 방지)
 */
@ConfigurationProperties("blog.notifications")
public record NotificationsProperties(
        @DefaultValue("90d") Duration retention,
        @DefaultValue("24h") Duration subscriberDedupWindow) {

    public NotificationsProperties {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("blog.notifications.retention must be positive");
        }
        if (subscriberDedupWindow.isNegative()) {
            throw new IllegalArgumentException("blog.notifications.subscriber-dedup-window must not be negative");
        }
    }
}
