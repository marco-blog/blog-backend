package net.java21.blog.backend.security;

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 정지된 회원 목록(005 FR-042, research M5). 정지하면 갱신 토큰은 모두 폐기하지만 이미 발급된 접근 토큰은 수명(30분)까지 유효하므로,
 * 그 수명 동안 이 목록에 있는 회원의 접근 토큰은 {@link JwtAuthenticationFilter}가 인증하지 않는다. 항목은 접근 토큰 수명이 지나면
 * 사라진다(그 뒤에는 새 접근 토큰을 받을 수 없다). 프로세스 안 Caffeine(서버 1대 전제), 재시작하면 비지만 토큰 수명 안의 짧은 틈만 남는다.
 * 등록·해제는 정지 트랜잭션이 커밋된 뒤에 한다(SuspensionService).
 */
@Component
public class SuspendedUserRegistry {

    private static final long MAX_ENTRIES = 100_000;

    private final Cache<Long, Boolean> suspended;

    @Autowired
    public SuspendedUserRegistry(AuthProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /** 테스트에서 시간을 움직일 때. */
    public SuspendedUserRegistry(AuthProperties properties, Ticker ticker) {
        this.suspended = Caffeine.newBuilder()
                .expireAfterWrite(properties.accessTtl().toNanos(), TimeUnit.NANOSECONDS)
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                .build();
    }

    public void add(long userId) {
        suspended.put(userId, Boolean.TRUE);
    }

    public void remove(long userId) {
        suspended.invalidate(userId);
    }

    public boolean contains(long userId) {
        return suspended.getIfPresent(userId) != null;
    }
}
