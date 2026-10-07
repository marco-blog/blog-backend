package net.java21.blog.backend.portal.service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import net.java21.blog.backend.topic.service.TopicService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * 포털 캐시의 주요 키({@code HOME}·{@code POPULARITY}·{@code LATEST:all:}·{@code TOPIC_TREE}·{@code TOPIC_COUNTS})를 방문자보다 먼저
 * 채운다(003 T130 후속). 기동 직후와 그 뒤 {@code blog.portal.cache-ttl}마다, 그리고 관리자 변경으로 캐시를 비운 직후
 * ({@link #warmSoon()}) 돈다. 주기 실행은 묵은 항목의 뒤 갱신만 시작하므로 금방 끝난다. 주제 페이지는 키가 많아 미리 채우지 않고,
 * 한 번 채워진 뒤로는 묵은 값을 주며 뒤에서 갱신한다({@link PortalCache}). {@code cache-ttl=0s}면 아무것도 하지 않는다.
 */
@Component
public class PortalCacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(PortalCacheWarmer.class);

    private final PortalCache cache;
    private final PortalService portalService;
    private final TopicService topicService;
    private final TaskScheduler scheduler;
    private final AtomicBoolean running = new AtomicBoolean();

    public PortalCacheWarmer(PortalCache cache, PortalService portalService, TopicService topicService,
            TaskScheduler scheduler) {
        this.cache = cache;
        this.portalService = portalService;
        this.topicService = topicService;
        this.scheduler = scheduler;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (cache.enabled()) {
            scheduler.scheduleWithFixedDelay(this::warm, Instant.now(), cache.ttl());
        }
    }

    /** 캐시를 비운 직후: 다음 방문자가 빈 캐시를 처음부터 계산하지 않도록 바로 다시 채우기 시작한다. */
    public void warmSoon() {
        if (cache.enabled()) {
            scheduler.schedule(this::warm, Instant.now());
        }
    }

    void warm() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        long started = System.nanoTime();
        try {
            portalService.home();
            portalService.latest(null);
            topicService.publicTree();
            log.debug("Portal cache warmed in {} ms", (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            log.warn("Portal cache warm-up failed; visitors will load on demand", e);
        } finally {
            running.set(false);
        }
    }
}
