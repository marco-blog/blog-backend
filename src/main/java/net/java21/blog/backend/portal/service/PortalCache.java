package net.java21.blog.backend.portal.service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.portal.PortalProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 포털 캐시(003 research P5, 결정 표 2번). Caffeine {@code expireAfterWrite = blog.portal.cache-ttl}(기본 5분)이라 글 상태 변화는
 * 최대 5분 뒤 반영되고(FR-090), 관리자 변경은 {@link #invalidateAll()}로 바로 반영한다. 값은 DTO(엔티티 아님)다.
 * {@code cache-ttl=0s}면 캐시하지 않고 매번 계산한다(통합 테스트·E2E). backend 1대 전제(001 R26).
 * <p>항목 키: {@code HOME}, {@code LATEST:{cursor}}, {@code TOPIC:{id}:{sort}:{page}:{size}}, {@code POPULARITY},
 * {@code TOPIC_COUNTS}, {@code TOPIC_TREE}.
 */
@Component
public class PortalCache {

    private final Cache<String, Object> cache;

    @Autowired
    public PortalCache(PortalProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /** 테스트에서 시간을 돌릴 때. */
    public PortalCache(PortalProperties properties, Ticker ticker) {
        long ttlNanos = properties.cacheTtl().toNanos();
        this.cache = ttlNanos <= 0 ? null : Caffeine.newBuilder()
                .expireAfterWrite(ttlNanos, TimeUnit.NANOSECONDS)
                .maximumSize(properties.cacheMaxSize())
                .ticker(ticker)
                .build();
    }

    /**
     * 캐시에 있으면 그 값, 없으면 계산해 넣는다. 계산 중에 다른 항목을 읽을 수 있도록(메인 묶음 → 인기 점수 스냅숏) Caffeine의
     * 원자적 계산({@code get(key, loader)})을 쓰지 않는다. 동시에 처음 요청이 몰리면 같은 값을 두 번 계산할 수 있다(결과는 같다).
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> loader) {
        if (cache == null) {
            return loader.get();
        }
        Object cached = cache.getIfPresent(key);
        if (cached != null) {
            return (T) cached;
        }
        T value = loader.get();
        if (value != null) {
            cache.put(key, value);
        }
        return value;
    }

    public void invalidateAll() {
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    public boolean enabled() {
        return cache != null;
    }
}
