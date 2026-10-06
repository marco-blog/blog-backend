package net.java21.blog.backend.portal.service;

import java.time.Clock;

import net.java21.blog.backend.setting.service.SystemSettingsService;
import org.springframework.stereotype.Component;

/** 지금 시각과 운영 설정값으로 {@link PortalCriteria}를 만든다(003 research P1·P3). */
@Component
public class PortalCriteriaFactory {

    private final Clock clock;
    private final SystemSettingsService settings;

    public PortalCriteriaFactory(Clock clock, SystemSettingsService settings) {
        this.clock = clock;
        this.settings = settings;
    }

    public PortalCriteria now() {
        return new PortalCriteria(clock.instant(), settings.newMemberDelay(), settings.minContentLength());
    }
}
