package net.java21.blog.backend.portal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.portal.PortalProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 포털 캐시의 갱신 방식(003 T130 후속): TTL이 지나면 묵은 값을 바로 주고 한 번만 뒤에서 다시 계산하며(stale-while-revalidate),
 * 처음 비어 있는 키는 동시에 몰려도 한 번만 계산한다(single-flight). TTL 0이면 캐시하지 않는다.
 */
class PortalCacheTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    /** 뒤에서 도는 갱신을 시험이 원할 때 돌린다. */
    private final Deque<Runnable> background = new ArrayDeque<>();
    private final Executor manual = background::add;
    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    private PortalCache cache(Executor executor) {
        return new PortalCache(properties(TTL), ticker, executor);
    }

    private static PortalProperties properties(Duration ttl) {
        PortalProperties d = PortalProperties.defaults();
        return new PortalProperties(ttl, d.cacheMaxSize(), d.scoreWeights(), d.newMemberDelay(), d.minContentLength(),
                d.topicAutoHideThreshold(), d.popularWindow(), d.topicCountWindow());
    }

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    private void runBackground() {
        while (!background.isEmpty()) {
            background.poll().run();
        }
    }

    @Test
    void freshValueIsServedWithoutCallingTheLoader() {
        PortalCache cache = cache(manual);
        AtomicInteger calls = new AtomicInteger();

        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(1);
        advance(TTL.minusSeconds(1));
        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(1);

        assertThat(calls).hasValue(1);
        assertThat(background).isEmpty();
    }

    @Test
    void afterTtlTheStaleValueIsServedAndOneRefreshRunsInTheBackground() {
        PortalCache cache = cache(manual);
        AtomicInteger calls = new AtomicInteger();
        cache.get("HOME", calls::incrementAndGet);
        advance(TTL);

        // 방문자는 기다리지 않는다: 묵은 값을 바로 받고, 갱신은 한 번만 예약된다
        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(1);
        assertThat(calls).hasValue(1);
        assertThat(background).hasSize(1);

        runBackground();
        assertThat(calls).hasValue(2);
        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(2);
        assertThat(background).isEmpty();
    }

    @Test
    void refreshReusesTheLoaderThatFilledTheEntry() {
        PortalCache cache = cache(manual);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger(100);
        cache.get("K", first::incrementAndGet);
        advance(TTL);

        cache.get("K", second::incrementAndGet);
        runBackground();

        assertThat(first).hasValue(2);
        assertThat(second).hasValue(100);
    }

    @Test
    void entriesFarPastTheTtlAreDroppedAndLoadedByTheCaller() {
        PortalCache cache = cache(manual);
        AtomicInteger calls = new AtomicInteger();
        cache.get("HOME", calls::incrementAndGet);

        advance(TTL.multipliedBy(PortalCache.MAX_STALE_TTLS));

        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(2);
        assertThat(background).isEmpty();
    }

    @Test
    void failedRefreshKeepsTheStaleValueAndRetriesOnTheNextRequest() {
        PortalCache cache = cache(manual);
        AtomicInteger calls = new AtomicInteger();
        cache.get("HOME", () -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("db down");
            }
            return calls.get();
        });
        advance(TTL);

        cache.get("HOME", () -> 0);
        runBackground();
        assertThat(calls).hasValue(2);
        assertThat(cache.get("HOME", () -> 0)).isEqualTo(1);

        runBackground();
        assertThat(calls).hasValue(3);
        assertThat(cache.get("HOME", () -> 0)).isEqualTo(3);
    }

    @Test
    void rejectedRefreshIsRetriedOnTheNextRequest() {
        AtomicInteger submits = new AtomicInteger();
        PortalCache cache = cache(task -> {
            if (submits.incrementAndGet() == 1) {
                throw new RejectedExecutionException("full");
            }
            background.add(task);
        });
        AtomicInteger calls = new AtomicInteger();
        cache.get("HOME", calls::incrementAndGet);
        advance(TTL);

        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(1);
        runBackground();

        assertThat(submits).hasValue(2);
        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void invalidationDropsValuesAndDiscardsARefreshThatStartedBefore() {
        PortalCache cache = cache(manual);
        AtomicInteger calls = new AtomicInteger();
        cache.get("HOME", calls::incrementAndGet);
        advance(TTL);
        cache.get("HOME", calls::incrementAndGet);

        cache.invalidateAll();
        runBackground(); // 비우기 전에 시작한 갱신: 결과(2)를 넣지 않는다

        assertThat(calls).hasValue(2);
        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(3);
    }

    @Test
    void concurrentMissesAreLoadedOnce() throws Exception {
        PortalCache cache = cache(manual);
        pool = Executors.newFixedThreadPool(4);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        Future<Integer> first = pool.submit(() -> cache.get("HOME", () -> {
            loading.countDown();
            await(release);
            return calls.incrementAndGet();
        }));
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Integer> second = pool.submit(() -> cache.get("HOME", calls::incrementAndGet));
        Future<Integer> third = pool.submit(() -> cache.get("HOME", calls::incrementAndGet));
        waitUntilWaiting(cache, "HOME", 2);
        release.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(third.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void aFailedLoadReachesEveryWaiterAndTheNextRequestTriesAgain() throws Exception {
        PortalCache cache = cache(manual);
        pool = Executors.newFixedThreadPool(2);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Integer> first = pool.submit(() -> cache.get("HOME", () -> {
            loading.countDown();
            await(release);
            throw new IllegalStateException("db down");
        }));
        assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Integer> second = pool.submit(() -> cache.get("HOME", () -> 99));
        waitUntilWaiting(cache, "HOME", 1);
        release.countDown();

        assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("db down");
        assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("db down");
        assertThat(cache.get("HOME", () -> 7)).isEqualTo(7);
    }

    @Test
    void aLoadThatOverlapsAnInvalidationIsReturnedButNotStored() {
        PortalCache cache = cache(manual);
        AtomicInteger calls = new AtomicInteger();

        assertThat(cache.get("HOME", () -> {
            cache.invalidateAll();
            return calls.incrementAndGet();
        })).isEqualTo(1);

        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(2);
    }

    @Test
    void loaderMayReadAnotherKey() {
        PortalCache cache = cache(manual);

        int home = cache.get("HOME", () -> cache.get("POPULARITY", () -> 41) + 1);

        assertThat(home).isEqualTo(42);
        assertThat(cache.get("POPULARITY", () -> 0)).isEqualTo(41);
    }

    @Test
    void nullIsNotCached() {
        PortalCache cache = cache(manual);
        AtomicInteger calls = new AtomicInteger();

        assertThat(cache.<Integer>get("K", () -> {
            calls.incrementAndGet();
            return null;
        })).isNull();
        cache.get("K", calls::incrementAndGet);

        assertThat(calls).hasValue(2);
    }

    @Test
    void zeroTtlComputesEveryTimeAndNeverUsesTheExecutor() {
        PortalCache cache = new PortalCache(properties(Duration.ZERO), ticker, manual);
        AtomicInteger calls = new AtomicInteger();

        cache.get("HOME", calls::incrementAndGet);
        cache.get("HOME", calls::incrementAndGet);
        cache.invalidateAll();

        assertThat(cache.enabled()).isFalse();
        assertThat(cache.ttl()).isEqualTo(Duration.ZERO);
        assertThat(calls).hasValue(2);
        assertThat(background).isEmpty();
    }

    @Test
    void defaultConstructorsRefreshInline() {
        PortalCache cache = new PortalCache(properties(TTL), ticker);
        AtomicInteger calls = new AtomicInteger();
        cache.get("HOME", calls::incrementAndGet);
        advance(TTL);

        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(1);
        assertThat(cache.get("HOME", calls::incrementAndGet)).isEqualTo(2);
        assertThat(new PortalCache(properties(TTL)).enabled()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void waitUntilWaiting(PortalCache cache, String key, int waiters) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cache.waiters(key) < waiters) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
    }
}
