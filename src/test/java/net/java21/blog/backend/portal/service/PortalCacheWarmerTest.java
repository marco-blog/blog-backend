package net.java21.blog.backend.portal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import net.java21.blog.backend.portal.PortalProperties;
import net.java21.blog.backend.topic.service.TopicService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.scheduling.TaskScheduler;

/** 포털 캐시 미리 채우기(003 T130 후속): 기동 직후와 TTL마다, 그리고 관리자 변경으로 비운 직후 주요 키를 채운다. */
class PortalCacheWarmerTest {

    private final PortalService portal = mock(PortalService.class);
    private final TopicService topics = mock(TopicService.class);
    private final TaskScheduler scheduler = mock(TaskScheduler.class);

    private PortalCacheWarmer warmer(Duration ttl) {
        PortalProperties d = PortalProperties.defaults();
        PortalCache cache = new PortalCache(new PortalProperties(ttl, d.cacheMaxSize(), d.scoreWeights(),
                d.newMemberDelay(), d.minContentLength(), d.topicAutoHideThreshold(), d.popularWindow(),
                d.topicCountWindow()));
        return new PortalCacheWarmer(cache, portal, topics, scheduler);
    }

    @Test
    void onReadyWarmsNowAndEveryTtl() {
        PortalCacheWarmer warmer = warmer(Duration.ofMinutes(5));

        warmer.onApplicationReady();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(task.capture(), any(Instant.class), eq(Duration.ofMinutes(5)));
        task.getValue().run();
        InOrder order = inOrder(portal, topics);
        order.verify(portal).home();
        order.verify(portal).latest(null);
        order.verify(topics).publicTree();
    }

    @Test
    void warmSoonSchedulesOneRun() {
        PortalCacheWarmer warmer = warmer(Duration.ofMinutes(5));

        warmer.warmSoon();

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(task.capture(), any(Instant.class));
        task.getValue().run();
        verify(portal).home();
    }

    @Test
    void zeroTtlNeverWarms() {
        PortalCacheWarmer warmer = warmer(Duration.ZERO);

        warmer.onApplicationReady();
        warmer.warmSoon();

        verifyNoInteractions(scheduler, portal, topics);
    }

    @Test
    void aFailureIsLoggedAndTheNextRunStillWorks() {
        PortalCacheWarmer warmer = warmer(Duration.ofMinutes(5));
        when(portal.home()).thenThrow(new IllegalStateException("db down")).thenReturn(null);

        warmer.warm();
        verify(topics, never()).publicTree();
        warmer.warm();

        verify(portal, times(2)).home();
        verify(topics).publicTree();
    }

    @Test
    void overlappingRunsAreSkipped() {
        PortalCacheWarmer warmer = warmer(Duration.ofMinutes(5));
        doAnswer(inv -> {
            warmer.warm(); // 진행 중에 또 불려도 건너뛴다
            return null;
        }).when(portal).home();

        warmer.warm();

        verify(portal, times(1)).home();
    }
}
