package net.java21.blog.backend.setting;

import net.java21.blog.backend.config.ExternalFeedProperties;
import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.spam.RateLimitProperties;
import net.java21.blog.backend.spam.SpamProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 운영 설정 키({@link SettingKey})의 기본값이 되는 프로퍼티 묶음(003 포털, 005 속도 제한·반복 스팸, 007 외부 블로그).
 */
@Component
public record SettingDefaults(PortalProperties portal, RateLimitProperties rateLimit, SpamProperties spam,
        ExternalFeedProperties external) {

    @Autowired
    public SettingDefaults {
    }

    /** 007 프로퍼티는 기본값(테스트용). */
    public SettingDefaults(PortalProperties portal, RateLimitProperties rateLimit, SpamProperties spam) {
        this(portal, rateLimit, spam, ExternalFeedProperties.defaults());
    }

    /** 포털 프로퍼티만 정하고 나머지는 기본값(테스트용). */
    public static SettingDefaults of(PortalProperties portal) {
        return new SettingDefaults(portal, RateLimitProperties.defaults(), SpamProperties.defaults(),
                ExternalFeedProperties.defaults());
    }

    /** 외부 블로그 프로퍼티만 정하고 나머지는 기본값(테스트용). */
    public static SettingDefaults of(ExternalFeedProperties external) {
        return new SettingDefaults(PortalProperties.defaults(), RateLimitProperties.defaults(),
                SpamProperties.defaults(), external);
    }
}
