package net.java21.blog.backend.portal.event;

import net.java21.blog.backend.portal.service.PortalCache;
import net.java21.blog.backend.portal.service.PortalCacheWarmer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** 관리자 변경이 커밋된 뒤(트랜잭션 밖이면 바로) 포털 캐시를 비우고 주요 키를 다시 채우기 시작한다(003 research P5, 결정 표 2번). */
@Component
public class PortalCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(PortalCacheInvalidator.class);

    private final PortalCache cache;
    private final PortalCacheWarmer warmer;

    public PortalCacheInvalidator(PortalCache cache, PortalCacheWarmer warmer) {
        this.cache = cache;
        this.warmer = warmer;
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onPortalChanged(PortalChangedEvent event) {
        cache.invalidateAll();
        warmer.warmSoon();
        log.debug("Portal cache invalidated: {}", event.reason());
    }
}
