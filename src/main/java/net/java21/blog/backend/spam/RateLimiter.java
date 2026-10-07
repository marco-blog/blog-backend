package net.java21.blog.backend.spam;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 공용 고정 창 속도 제한(005 FR-142, research M8). (종류, 주체)마다 창 시작 시각과 횟수를 프로세스 안 Caffeine 캐시에 둔다(서버 1대
 * 전제). 한도를 넘으면 429 {@code TOO_MANY_REQUESTS} + {@code Retry-After}(창이 끝날 때까지 남은 초, 올림). 한도·창은 호출마다
 * 받으므로 운영 설정 변경이 다음 호출부터 반영된다. 항목은 자기 창이 끝나면 사라진다.
 */
@Component
public class RateLimiter {

    private static final long MAX_ENTRIES = 200_000;
    private static final long SECOND_NANOS = TimeUnit.SECONDS.toNanos(1);

    /** 창 시작(ticker 나노초), 창 길이, 그 창의 횟수. */
    private record Window(long startNanos, long windowNanos, int count) {
    }

    private final Cache<String, Window> windows;
    private final Ticker ticker;

    @Autowired
    public RateLimiter() {
        this(Ticker.systemTicker());
    }

    /** 테스트에서 시간을 움직일 때. */
    public RateLimiter(Ticker ticker) {
        this.ticker = ticker;
        this.windows = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, Window>() {
                    @Override
                    public long expireAfterCreate(String key, Window value, long currentTime) {
                        return Math.max(0, value.startNanos() + value.windowNanos() - currentTime);
                    }

                    @Override
                    public long expireAfterUpdate(String key, Window value, long currentTime, long currentDuration) {
                        return Math.max(0, value.startNanos() + value.windowNanos() - currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, Window value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                .build();
    }

    /**
     * 한 번 센다. 한도를 넘으면 예외(넘은 호출도 센다).
     *
     * @throws BusinessException 429 {@code TOO_MANY_REQUESTS} + {@code Retry-After}
     */
    public void check(RateLimitKind kind, String subject, int limit, Duration window) {
        long remaining = hit(kind, subject, limit, window);
        if (remaining > 0) {
            throw BusinessException.retryAfter(ErrorCode.TOO_MANY_REQUESTS, "Too many requests: " + kind,
                    (remaining + SECOND_NANOS - 1) / SECOND_NANOS);
        }
    }

    /** 한 번 세고 한도 안이면 true. 예외 대신 결과가 필요한 곳(트랙백 받기의 XML 응답 등). */
    public boolean tryAcquire(RateLimitKind kind, String subject, int limit, Duration window) {
        return hit(kind, subject, limit, window) == 0;
    }

    /** 세지 않고 지금 창의 횟수만 본다(로그인 실패 수 등). */
    public int count(RateLimitKind kind, String subject) {
        Window current = windows.getIfPresent(key(kind, subject));
        if (current == null || ticker.read() - current.startNanos() >= current.windowNanos()) {
            return 0;
        }
        return current.count();
    }

    /** 그 주체의 창을 지운다(로그인 성공 뒤 실패 수 초기화 등). */
    public void reset(RateLimitKind kind, String subject) {
        windows.invalidate(key(kind, subject));
    }

    /** @return 한도 안이면 0, 넘었으면 창이 끝날 때까지 남은 나노초(1 이상) */
    private long hit(RateLimitKind kind, String subject, int limit, Duration window) {
        long windowNanos = window.toNanos();
        long now = ticker.read();
        Window updated = windows.asMap().compute(key(kind, subject), (key, current) -> {
            if (current == null || now - current.startNanos() >= current.windowNanos()) {
                return new Window(now, windowNanos, 1);
            }
            return new Window(current.startNanos(), current.windowNanos(), current.count() + 1);
        });
        if (updated.count() <= limit) {
            return 0;
        }
        return Math.max(1, updated.windowNanos() - (now - updated.startNanos()));
    }

    private static String key(RateLimitKind kind, String subject) {
        return kind.name() + '|' + (subject == null ? "" : subject);
    }
}
