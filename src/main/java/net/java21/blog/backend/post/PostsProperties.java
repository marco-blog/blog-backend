package net.java21.blog.backend.post;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 글 설정.
 *
 * @param viewDedupTtl     같은 사람(회원 ID 또는 방문자 쿠키)의 재조회를 세지 않는 기간(FR-020, research R10)
 * @param viewDedupMaxSize 조회 중복 판단 캐시의 최대 항목 수(넘으면 오래된 것부터 버린다)
 * @param visitorCookie    익명 방문자 쿠키 이름(research R19). 조회수 API가 없으면 발급한다
 * @param visitorCookieMaxAge 방문자 쿠키 수명(research R19: 1년)
 */
@ConfigurationProperties("blog.posts")
public record PostsProperties(
        @DefaultValue("30m") Duration viewDedupTtl,
        @DefaultValue("100000") long viewDedupMaxSize,
        @DefaultValue("visitor_id") String visitorCookie,
        @DefaultValue("365d") Duration visitorCookieMaxAge) {
}
