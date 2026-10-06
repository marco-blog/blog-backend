package net.java21.blog.backend.portal;

import java.time.Duration;

import net.java21.blog.backend.portal.service.ScoreWeights;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 포털 설정(003 contracts/api.md "프로퍼티"). {@code scoreWeights}·{@code newMemberDelay}·{@code minContentLength}·
 * {@code topicAutoHideThreshold}는 운영 설정({@code system_settings})에 행이 없을 때의 기본값이다(research P3).
 *
 * @param cacheTtl               포털 목록·인기 점수·주제별 글 수 캐시 수명(FR-090 반영 한도). {@code 0s}면 캐시하지 않는다
 * @param cacheMaxSize           포털 캐시 최대 항목 수
 * @param newMemberDelay         가입 후 포털 노출까지 대기(FR-088)
 * @param minContentLength       포털 노출 최소 본문 길이(문자 수, FR-088)
 * @param topicAutoHideThreshold 주제 자동 숨김 기준(최근 30일 글 수, FR-147). 0이면 모든 주제가 탭에 보인다
 * @param popularWindow          인기 점수·인기 태그 집계 기간
 * @param topicCountWindow       주제 자동 숨김 글 수·새로 시작한 블로그 기간
 */
@ConfigurationProperties("blog.portal")
public record PortalProperties(
        @DefaultValue("5m") Duration cacheTtl,
        @DefaultValue("2000") long cacheMaxSize,
        @DefaultValue ScoreWeights scoreWeights,
        @DefaultValue("24h") Duration newMemberDelay,
        @DefaultValue("200") int minContentLength,
        @DefaultValue("20") int topicAutoHideThreshold,
        @DefaultValue("7d") Duration popularWindow,
        @DefaultValue("30d") Duration topicCountWindow) {

    /** 기본값만 쓰는 설정(단위 테스트용). */
    public static PortalProperties defaults() {
        return new PortalProperties(Duration.ofMinutes(5), 2000, new ScoreWeights(1, 5, 10, 8, 48, 0.5),
                Duration.ofHours(24), 200, 20, Duration.ofDays(7), Duration.ofDays(30));
    }
}
