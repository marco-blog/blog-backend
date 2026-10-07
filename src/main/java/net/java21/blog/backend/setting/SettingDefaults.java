package net.java21.blog.backend.setting;

import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.spam.RateLimitProperties;
import net.java21.blog.backend.spam.SpamProperties;
import org.springframework.stereotype.Component;

/**
 * 운영 설정 키({@link SettingKey})의 기본값이 되는 프로퍼티 묶음(003 포털, 005 속도 제한·반복 스팸). 007이 키를 더하면 여기에 프로퍼티를
 * 더한다.
 */
@Component
public record SettingDefaults(PortalProperties portal, RateLimitProperties rateLimit, SpamProperties spam) {

    /** 포털 프로퍼티만 정하고 나머지는 기본값(테스트용). */
    public static SettingDefaults of(PortalProperties portal) {
        return new SettingDefaults(portal, RateLimitProperties.defaults(), SpamProperties.defaults());
    }
}
