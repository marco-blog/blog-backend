package net.java21.blog.backend.guest.service;

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.guest.GuestProperties;
import net.java21.blog.backend.guest.dto.GuestWriteKind;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * IP당 1분 고정 창 속도 제한(004 research B6): 비회원 댓글 {@code blog.guest.comment-per-minute}(5), 방명록
 * {@code blog.guest.guestbook-per-minute}(3). 넘으면 429 {@code TOO_MANY_REQUESTS} + {@code Retry-After}(창이 끝날 때까지 남은 초).
 * 프로세스 안 Caffeine 캐시(서버 1대 전제).
 */
@Component
public class RateLimitGuestWriteGuard implements GuestWriteGuard {

    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final long MAX_ENTRIES = 100_000;

    /** 창 시작 시각(ticker 나노초)과 그 창의 쓰기 수. */
    private record Window(long startNanos, int count) {
    }

    private final Cache<String, Window> windows;
    private final Ticker ticker;
    private final GuestProperties properties;

    @Autowired
    public RateLimitGuestWriteGuard(GuestProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /** 테스트에서 시간을 움직일 때. */
    public RateLimitGuestWriteGuard(GuestProperties properties, Ticker ticker) {
        this.properties = properties;
        this.ticker = ticker;
        this.windows = Caffeine.newBuilder()
                .expireAfterWrite(WINDOW_NANOS, TimeUnit.NANOSECONDS)
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                .build();
    }

    @Override
    public void check(GuestWriteKind kind, String ip) {
        int limit = kind == GuestWriteKind.COMMENT ? properties.commentPerMinute() : properties.guestbookPerMinute();
        long now = ticker.read();
        Window window = windows.asMap().compute(kind + "|" + (ip == null ? "" : ip), (key, current) -> {
            if (current == null || now - current.startNanos() >= WINDOW_NANOS) {
                return new Window(now, 1);
            }
            return new Window(current.startNanos(), current.count() + 1);
        });
        if (window.count() > limit) {
            long remaining = WINDOW_NANOS - (now - window.startNanos());
            long seconds = (remaining + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1);
            throw BusinessException.retryAfter(ErrorCode.TOO_MANY_REQUESTS, "Too many guest writes: " + kind, seconds);
        }
    }
}
