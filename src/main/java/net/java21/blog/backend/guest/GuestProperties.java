package net.java21.blog.backend.guest;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 비회원 쓰기 설정(004 contracts/api.md "프로퍼티"). 잘못된 값이면 기동하지 않는다. 004의 {@code comment-per-minute}·
 * {@code guestbook-per-minute}는 005에서 {@code blog.ratelimit.*}(운영 설정 {@code ratelimit.*})로 옮겼다(005 research M8).
 *
 * @param ipRetention 비회원 작성 IP 보관 기간(지나면 개인정보 파기 작업이 지운다)
 */
@ConfigurationProperties("blog.guest")
public record GuestProperties(@DefaultValue("90d") Duration ipRetention) {

    public GuestProperties {
        if (ipRetention == null || ipRetention.isNegative() || ipRetention.isZero()) {
            throw new IllegalArgumentException("blog.guest.ip-retention must be positive");
        }
    }

    /** 기본값(테스트용). */
    public static GuestProperties defaults() {
        return new GuestProperties(Duration.ofDays(90));
    }
}
