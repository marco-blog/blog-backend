package net.java21.blog.backend.spam;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

/** 005 T008: 공용 고정 창 속도 제한. */
class RateLimiterTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);

    private final AtomicLong nanos = new AtomicLong();
    private final RateLimiter limiter = new RateLimiter(nanos::get);

    private void allow(RateLimitKind kind, String subject, int limit, int times) {
        for (int i = 0; i < times; i++) {
            assertThatCode(() -> limiter.check(kind, subject, limit, MINUTE)).doesNotThrowAnyException();
        }
    }

    @Test
    void passesUpToLimitThenTooManyRequestsWithRetryAfter() {
        allow(RateLimitKind.COMMENT, "u:1", 5, 5);
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(20));
        assertThatThrownBy(() -> limiter.check(RateLimitKind.COMMENT, "u:1", 5, MINUTE))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat(e.retryAfterSeconds()).isEqualTo(40L);
                });
    }

    @Test
    void newWindowAfterItEnds() {
        allow(RateLimitKind.GUESTBOOK, "ip:1", 3, 3);
        assertCode(() -> limiter.check(RateLimitKind.GUESTBOOK, "ip:1", 3, MINUTE), ErrorCode.TOO_MANY_REQUESTS);
        nanos.addAndGet(MINUTE.toNanos());
        allow(RateLimitKind.GUESTBOOK, "ip:1", 3, 3);
    }

    @Test
    void subjectsAndKindsAreCountedSeparately() {
        allow(RateLimitKind.GUESTBOOK, "ip:1", 3, 3);
        allow(RateLimitKind.GUESTBOOK, "ip:2", 3, 3);
        allow(RateLimitKind.GUESTBOOK, null, 3, 3);
        allow(RateLimitKind.COMMENT, "ip:1", 3, 3);
    }

    @Test
    void changedLimitAppliesFromNextCall() {
        allow(RateLimitKind.REPORT, "u:1", 2, 2);
        assertThat(limiter.tryAcquire(RateLimitKind.REPORT, "u:1", 2, MINUTE)).isFalse();
        assertThat(limiter.tryAcquire(RateLimitKind.REPORT, "u:1", 100, MINUTE)).isTrue();
    }

    @Test
    void countAndReset() {
        assertThat(limiter.count(RateLimitKind.LOGIN_FAILURE, "e")).isZero();
        limiter.tryAcquire(RateLimitKind.LOGIN_FAILURE, "e", 10, MINUTE);
        limiter.tryAcquire(RateLimitKind.LOGIN_FAILURE, "e", 10, MINUTE);
        assertThat(limiter.count(RateLimitKind.LOGIN_FAILURE, "e")).isEqualTo(2);
        nanos.addAndGet(MINUTE.toNanos());
        assertThat(limiter.count(RateLimitKind.LOGIN_FAILURE, "e")).isZero();
        limiter.tryAcquire(RateLimitKind.LOGIN_FAILURE, "e", 10, MINUTE);
        limiter.reset(RateLimitKind.LOGIN_FAILURE, "e");
        assertThat(limiter.count(RateLimitKind.LOGIN_FAILURE, "e")).isZero();
    }

    @Test
    void exactlyLimitPassUnderConcurrency() throws Exception {
        RateLimiter real = new RateLimiter();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 100; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return real.tryAcquire(RateLimitKind.SIGNUP, "ip:9", 10, Duration.ofHours(1));
                }));
            }
            start.countDown();
            int passed = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    passed++;
                }
            }
            assertThat(passed).isEqualTo(10);
        } finally {
            pool.shutdownNow();
        }
    }
}
