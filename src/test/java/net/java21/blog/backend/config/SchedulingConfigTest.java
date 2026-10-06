package net.java21.blog.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

/** 정기 작업 설정(007 research E1): 스케줄러 3개, 피드 수집 전용 풀 4개, application.yml 값으로 확인한다. */
@SpringBootTest(
        classes = {SchedulingConfig.class, TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SchedulingConfigTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    @Qualifier("feedFetchExecutor")
    private ThreadPoolTaskExecutor feedFetchExecutor;

    @Test
    void schedulingIsEnabledWithThreeThreads() {
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isNotEmpty();
        ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(3);
    }

    @Test
    void feedFetchExecutorHasFourThreadsBoundedQueueAndGracefulShutdown() {
        assertThat(feedFetchExecutor.getCorePoolSize()).isEqualTo(4);
        assertThat(feedFetchExecutor.getMaxPoolSize()).isEqualTo(4);
        assertThat(feedFetchExecutor.getQueueCapacity()).isEqualTo(50);
        assertThat(feedFetchExecutor.getThreadNamePrefix()).isEqualTo("feed-fetch-");
        assertThat(ReflectionTestUtils.getField(feedFetchExecutor, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(feedFetchExecutor, "awaitTerminationMillis")).isEqualTo(30_000L);
    }

    @Test
    void defaultApplicationExecutorIsKeptSeparate() {
        assertThat(context.containsBean("applicationTaskExecutor")).isTrue();
        assertThat(context.getBean("applicationTaskExecutor")).isNotSameAs(feedFetchExecutor);
    }

    @Test
    void fullQueueRejectsSoTheSchedulerCanSkipThisRound() throws Exception {
        ThreadPoolTaskExecutor executor = new SchedulingConfig().feedFetchExecutor(new ExternalFeedProperties(1, 1));
        executor.initialize();
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> await(release));
            executor.execute(() -> await(release));
            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .isInstanceOf(TaskRejectedException.class);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
