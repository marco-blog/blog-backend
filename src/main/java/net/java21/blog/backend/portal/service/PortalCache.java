package net.java21.blog.backend.portal.service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.portal.PortalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 포털 캐시(003 research P5, 결정 표 2번). 값은 DTO(엔티티 아님)이고, backend 1대 전제(001 R26)다.
 * <ul>
 * <li><b>신선</b>(쓴 지 {@code blog.portal.cache-ttl}, 기본 5분 미만): 그대로 준다.</li>
 * <li><b>묵음</b>(TTL 이상, {@link #MAX_STALE_TTLS}×TTL 미만): 묵은 값을 <b>바로</b> 주고, 그 키의 갱신을 뒤에서 한 번만 돌린다
 * (stale-while-revalidate). 방문자는 다시 계산(대량 데이터에서 수 초)을 기다리지 않는다. 글 상태 변화는 TTL 뒤 첫 요청이
 * 갱신을 시작하고 그 갱신이 끝나면 반영된다(FR-090).</li>
 * <li><b>없음</b>(처음, {@link #MAX_STALE_TTLS}×TTL 이상 지나 버림, 비운 뒤): 요청한 스레드가 계산한다. 같은 키에 동시에 온 요청은
 * 그 계산 하나를 기다려 같은 값을 받는다(single-flight).</li>
 * </ul>
 * 관리자 변경은 {@link #invalidateAll()}로 바로 반영한다(FR-094). 비우기 전에 시작한 계산 결과는 캐시에 넣지 않는다.
 * {@code cache-ttl=0s}면 캐시하지 않고 매번 계산한다(통합 테스트·E2E). 주요 키는 {@link PortalCacheWarmer}가 미리 채운다.
 * <p>항목 키: {@code HOME}, {@code LATEST:{source}:{cursor}}, {@code TOPIC:{id}:{sort}:{page}:{size}}, {@code POPULARITY},
 * {@code TOPIC_COUNTS}, {@code TOPIC_TREE}. 계산 함수는 키만으로 결과가 정해져야 한다(뒤에서 다시 부르므로 요청 시각 등을 붙잡지 않는다).
 */
@Component
public class PortalCache implements DisposableBean {

    /** 이만큼(TTL의 배수) 갱신되지 않은 항목은 묵은 값으로도 주지 않고 버린다(오래 찾지 않은 주제 페이지 등). */
    static final int MAX_STALE_TTLS = 12;
    private static final int REFRESH_THREADS = 2;
    private static final int REFRESH_QUEUE = 100;
    private static final Logger log = LoggerFactory.getLogger(PortalCache.class);

    private final Cache<String, Entry> cache;
    private final long ttlNanos;
    private final Ticker ticker;
    private final Executor refreshExecutor;
    private final TransactionTemplate readOnlyTx;
    private final ConcurrentMap<String, Flight> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();

    @Autowired
    public PortalCache(PortalProperties properties, ObjectProvider<PlatformTransactionManager> transactionManager) {
        this(properties, Ticker.systemTicker(), newRefreshExecutor(), readOnly(transactionManager.getIfAvailable()));
    }

    /** 단위 테스트: 갱신을 요청 스레드에서 바로 돌린다. */
    public PortalCache(PortalProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /** 단위 테스트에서 시간을 돌릴 때. 갱신은 요청 스레드에서 바로 돈다. */
    public PortalCache(PortalProperties properties, Ticker ticker) {
        this(properties, ticker, Runnable::run);
    }

    /** 단위 테스트: 갱신을 돌릴 실행기를 정한다. */
    PortalCache(PortalProperties properties, Ticker ticker, Executor refreshExecutor) {
        this(properties, ticker, refreshExecutor, null);
    }

    private PortalCache(PortalProperties properties, Ticker ticker, Executor refreshExecutor,
            TransactionTemplate readOnlyTx) {
        this.ttlNanos = Math.max(0, properties.cacheTtl().toNanos());
        this.ticker = ticker;
        this.refreshExecutor = refreshExecutor;
        this.readOnlyTx = readOnlyTx;
        this.cache = ttlNanos == 0 ? null : Caffeine.newBuilder()
                .expireAfterWrite(Math.multiplyExact(ttlNanos, MAX_STALE_TTLS), TimeUnit.NANOSECONDS)
                .maximumSize(properties.cacheMaxSize())
                .ticker(ticker)
                .build();
    }

    /**
     * 신선하면 그 값, 묵었으면 묵은 값(그리고 뒤에서 갱신 한 번), 없으면 계산해 넣는다. 계산 중에 다른 키를 읽을 수 있다(메인 묶음 →
     * 인기 점수 스냅숏). 계산이 실패하면 기다리던 요청 모두에 같은 예외가 가고 아무것도 넣지 않는다. null은 넣지 않는다.
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> loader) {
        if (cache == null) {
            return loader.get();
        }
        Entry entry = cache.getIfPresent(key);
        if (entry != null) {
            if (ticker.read() - entry.writtenAt() >= ttlNanos) {
                refreshInBackground(key, entry);
            }
            return (T) entry.value();
        }
        return (T) loadOnce(key, loader);
    }

    private Object loadOnce(String key, Supplier<?> loader) {
        Flight mine = new Flight(generation.get());
        Flight running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            running.waiters.incrementAndGet();
            try {
                return running.future.join();
            } catch (CompletionException e) {
                throw rethrow(e.getCause());
            }
        }
        try {
            Object value = loader.get();
            store(key, value, loader, mine.generation);
            mine.future.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.future.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    private void refreshInBackground(String key, Entry stale) {
        if (!stale.refreshing().compareAndSet(false, true)) {
            return;
        }
        long startedIn = generation.get();
        try {
            refreshExecutor.execute(() -> {
                try {
                    Object value = readOnlyTx == null ? stale.loader().get()
                            : readOnlyTx.execute(status -> stale.loader().get());
                    store(key, value, stale.loader(), startedIn);
                } catch (RuntimeException | Error e) {
                    log.warn("Portal cache refresh failed for {}; serving the previous value", key, e);
                } finally {
                    stale.refreshing().set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            stale.refreshing().set(false);
            log.debug("Portal cache refresh for {} skipped: executor is busy", key);
        }
    }

    private void store(String key, Object value, Supplier<?> loader, long startedIn) {
        if (value == null) {
            return;
        }
        synchronized (generation) {
            if (generation.get() == startedIn) {
                cache.put(key, new Entry(value, ticker.read(), loader, new AtomicBoolean()));
            }
        }
    }

    public void invalidateAll() {
        if (cache != null) {
            synchronized (generation) {
                generation.incrementAndGet();
                cache.invalidateAll();
                inFlight.clear();
            }
        }
    }

    public boolean enabled() {
        return cache != null;
    }

    public Duration ttl() {
        return Duration.ofNanos(ttlNanos);
    }

    /** 시험용: 이 키의 진행 중 계산을 기다리는 요청 수. */
    int waiters(String key) {
        Flight flight = inFlight.get(key);
        return flight == null ? 0 : flight.waiters.get();
    }

    @Override
    public void destroy() {
        if (refreshExecutor instanceof ExecutorService service) {
            service.shutdownNow();
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            return runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException(cause);
    }

    private static TransactionTemplate readOnly(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            return null;
        }
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    /** 갱신 전용 풀(작게, 큐가 차면 이번 갱신은 건너뛰고 다음 요청이 다시 시도한다). */
    private static ExecutorService newRefreshExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(REFRESH_THREADS, REFRESH_THREADS, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(REFRESH_QUEUE), runnable -> {
                    Thread thread = new Thread(runnable, "portal-cache-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private record Entry(Object value, long writtenAt, Supplier<?> loader, AtomicBoolean refreshing) {
    }

    private static final class Flight {
        private final long generation;
        private final CompletableFuture<Object> future = new CompletableFuture<>();
        private final AtomicInteger waiters = new AtomicInteger();

        private Flight(long generation) {
            this.generation = generation;
        }
    }
}
