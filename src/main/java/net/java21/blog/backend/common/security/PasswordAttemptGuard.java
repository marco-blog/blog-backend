package net.java21.blog.backend.common.security;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.PostsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 보호 글·비회원 댓글·비회원 방명록 비밀번호의 공통 시도 제한(004 research B3, FR-063, FR-066).
 * 대상({@link AttemptTarget})마다 방문자 키({@code u:{id}}·{@code v:{uuid}})와 접속 IP 각각의 연속 실패 수를 센다. 어느 키든
 * {@code blog.posts.password-max-failures}(5)에 닿으면 마지막 실패부터 {@code blog.posts.password-lock-duration}(10분) 동안
 * 그 대상의 비밀번호 확인을 429 {@code PASSWORD_ATTEMPTS_EXCEEDED}({@code Retry-After} 남은 초)로 거부한다. 맞으면 두 키의 실패 수를
 * 지운다. 프로세스 안 Caffeine 캐시(서버 1대 전제, 001 R26)이며 테이블이 없다.
 * <pre>{@code
 * guard.check(target, visitorKey, ip);           // 막혔으면 429
 * if (matches) guard.recordSuccess(target, visitorKey, ip);
 * else { guard.recordFailure(target, visitorKey, ip); throw mismatch; }
 * }</pre>
 */
@Component
public class PasswordAttemptGuard {

    private static final long MAX_ENTRIES = 200_000;

    /** 연속 실패 수와 마지막 실패 시각(ticker 나노초). */
    private record Failures(int count, long lastFailureNanos) {
    }

    private final Cache<String, Failures> failures;
    private final Ticker ticker;
    private final int maxFailures;
    private final long lockNanos;

    @Autowired
    public PasswordAttemptGuard(PostsProperties properties) {
        this(properties, Ticker.systemTicker());
    }

    /** 테스트에서 시간을 움직일 때. */
    public PasswordAttemptGuard(PostsProperties properties, Ticker ticker) {
        this.ticker = ticker;
        this.maxFailures = properties.passwordMaxFailures();
        this.lockNanos = properties.passwordLockDuration().toNanos();
        this.failures = Caffeine.newBuilder()
                .expireAfterWrite(lockNanos, TimeUnit.NANOSECONDS)
                .maximumSize(MAX_ENTRIES)
                .ticker(ticker)
                .build();
    }

    /** 이 대상에 이 방문자 또는 IP가 막혀 있으면 429 {@code PASSWORD_ATTEMPTS_EXCEEDED}(남은 초). */
    public void check(AttemptTarget target, String visitorKey, String ip) {
        long remaining = 0;
        for (String key : keys(target, visitorKey, ip)) {
            Failures current = failures.getIfPresent(key);
            if (current != null && current.count() >= maxFailures) {
                long elapsed = ticker.read() - current.lastFailureNanos();
                remaining = Math.max(remaining, lockNanos - elapsed);
            }
        }
        if (remaining > 0) {
            long seconds = (remaining + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1);
            throw BusinessException.retryAfter(ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED,
                    "Too many password attempts: " + target.key(), seconds);
        }
    }

    /** 틀린 비밀번호 한 번을 두 키에 센다. */
    public void recordFailure(AttemptTarget target, String visitorKey, String ip) {
        long now = ticker.read();
        for (String key : keys(target, visitorKey, ip)) {
            failures.asMap().compute(key, (k, current) ->
                    new Failures(current == null ? 1 : current.count() + 1, now));
        }
    }

    /** 맞는 비밀번호: 두 키의 실패 수를 지운다. */
    public void recordSuccess(AttemptTarget target, String visitorKey, String ip) {
        failures.invalidateAll(keys(target, visitorKey, ip));
    }

    private static List<String> keys(AttemptTarget target, String visitorKey, String ip) {
        List<String> keys = new ArrayList<>(2);
        if (visitorKey != null && !visitorKey.isBlank()) {
            keys.add(target.key() + "|k|" + visitorKey);
        }
        if (ip != null && !ip.isBlank()) {
            keys.add(target.key() + "|ip|" + ip);
        }
        return keys;
    }
}
