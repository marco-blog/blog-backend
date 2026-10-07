package net.java21.blog.backend.spam;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 작성 속도 한도의 기본값(005 FR-142, contracts/api.md "프로퍼티"). 운영 설정 키({@code ratelimit.*}, 003 {@code system_settings})에
 * 값이 없을 때 쓴다. 1 미만이면 기동하지 않는다. E2E·로컬 시험은 {@code BLOG_RATELIMIT_*}로 넉넉하게 띄운다(research M18).
 *
 * @param postPublishPerHour    회원의 처음 발행·예약(모든 블로그 합계) 1시간 한도
 * @param commentPerMinute      회원 ID 또는 비회원 IP의 댓글 1분 한도(004 {@code blog.guest.comment-per-minute}를 대체)
 * @param guestbookPerMinute    회원 ID 또는 비회원 IP의 방명록 1분 한도(004 {@code blog.guest.guestbook-per-minute}를 대체)
 * @param mediaUploadPerMinute  회원의 이미지 업로드 1분 한도
 * @param signupPerIpPerHour    같은 IP의 가입 1시간 한도
 */
@ConfigurationProperties("blog.ratelimit")
public record RateLimitProperties(
        @DefaultValue("10") int postPublishPerHour,
        @DefaultValue("5") int commentPerMinute,
        @DefaultValue("3") int guestbookPerMinute,
        @DefaultValue("30") int mediaUploadPerMinute,
        @DefaultValue("5") int signupPerIpPerHour) {

    public RateLimitProperties {
        positive("post-publish-per-hour", postPublishPerHour);
        positive("comment-per-minute", commentPerMinute);
        positive("guestbook-per-minute", guestbookPerMinute);
        positive("media-upload-per-minute", mediaUploadPerMinute);
        positive("signup-per-ip-per-hour", signupPerIpPerHour);
    }

    /** 기본값(테스트용). */
    public static RateLimitProperties defaults() {
        return new RateLimitProperties(10, 5, 3, 30, 5);
    }

    private static void positive(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException("blog.ratelimit." + name + " must be at least 1");
        }
    }
}
