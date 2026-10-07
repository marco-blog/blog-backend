package net.java21.blog.backend.guest;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 비회원 쓰기 설정(004 contracts/api.md "프로퍼티", research B6). 잘못된 값이면 기동하지 않는다.
 *
 * @param commentPerMinute   같은 IP의 비회원 댓글 1분 한도
 * @param guestbookPerMinute 같은 IP의 비회원 방명록 1분 한도
 * @param ipRetention        비회원 작성 IP 보관 기간(지나면 개인정보 파기 작업이 지운다)
 */
@ConfigurationProperties("blog.guest")
public record GuestProperties(
        @DefaultValue("5") int commentPerMinute,
        @DefaultValue("3") int guestbookPerMinute,
        @DefaultValue("90d") Duration ipRetention) {

    public GuestProperties {
        if (commentPerMinute < 1) {
            throw new IllegalArgumentException("blog.guest.comment-per-minute must be at least 1");
        }
        if (guestbookPerMinute < 1) {
            throw new IllegalArgumentException("blog.guest.guestbook-per-minute must be at least 1");
        }
        if (ipRetention == null || ipRetention.isNegative() || ipRetention.isZero()) {
            throw new IllegalArgumentException("blog.guest.ip-retention must be positive");
        }
    }

    /** 기본값(테스트용). */
    public static GuestProperties defaults() {
        return new GuestProperties(5, 3, Duration.ofDays(90));
    }
}
