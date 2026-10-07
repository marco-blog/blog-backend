package net.java21.blog.backend.common.security;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.PostsProperties;
import org.junit.jupiter.api.Test;

/** 004 T006: 같은 대상에 방문자 키 또는 IP가 5회 연속 틀리면 10분 동안 429(research B3, FR-063). */
class PasswordAttemptGuardTest {

    private long nanos;
    private final PasswordAttemptGuard guard = new PasswordAttemptGuard(
            new PostsProperties(Duration.ofMinutes(30), 10, "v", Duration.ofDays(1)), () -> nanos);

    private static final AttemptTarget POST = AttemptTarget.post(1L);

    private void fail(AttemptTarget target, String key, String ip, int times) {
        for (int i = 0; i < times; i++) {
            guard.check(target, key, ip);
            guard.recordFailure(target, key, ip);
        }
    }

    @Test
    void fifthConsecutiveFailureLocksWithRemainingSeconds() {
        fail(POST, "v:a", "1.1.1.1", 4);
        assertThatCode(() -> guard.check(POST, "v:a", "1.1.1.1")).doesNotThrowAnyException();
        guard.recordFailure(POST, "v:a", "1.1.1.1");

        nanos += TimeUnit.SECONDS.toNanos(60);
        assertThatThrownBy(() -> guard.check(POST, "v:a", "1.1.1.1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    org.assertj.core.api.Assertions.assertThat(e.errorCode())
                            .isEqualTo(ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED);
                    org.assertj.core.api.Assertions.assertThat(e.retryAfterSeconds()).isEqualTo(540L);
                });
    }

    @Test
    void eitherKeyLocks() {
        fail(POST, "v:a", "1.1.1.1", 5);
        // 쿠키를 지워도 같은 IP면 막힌다
        assertCode(() -> guard.check(POST, "v:other", "1.1.1.1"), ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED);
        // 다른 IP라도 같은 방문자면 막힌다
        assertCode(() -> guard.check(POST, "v:a", "2.2.2.2"), ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED);
        assertThatCode(() -> guard.check(POST, "v:other", "2.2.2.2")).doesNotThrowAnyException();
    }

    @Test
    void unlocksAfterLockDuration() {
        fail(POST, "v:a", "1.1.1.1", 5);
        nanos += TimeUnit.MINUTES.toNanos(10) + 1;
        assertThatCode(() -> guard.check(POST, "v:a", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void successResetsBothKeys() {
        fail(POST, "v:a", "1.1.1.1", 4);
        guard.recordSuccess(POST, "v:a", "1.1.1.1");
        fail(POST, "v:a", "1.1.1.1", 4);
        assertThatCode(() -> guard.check(POST, "v:a", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void targetsAndKindsAreSeparate() {
        fail(POST, "v:a", "1.1.1.1", 5);
        assertThatCode(() -> guard.check(AttemptTarget.post(2L), "v:a", "1.1.1.1")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(AttemptTarget.comment(1L), "v:a", "1.1.1.1")).doesNotThrowAnyException();
        assertThatCode(() -> guard.check(AttemptTarget.guestbook(1L), "v:a", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void missingKeysAreIgnored() {
        fail(POST, null, " ", 10);
        assertThatCode(() -> guard.check(POST, null, null)).doesNotThrowAnyException();
    }
}
