package net.java21.blog.backend.guest.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.guest.GuestProperties;
import net.java21.blog.backend.guest.dto.GuestWriteKind;
import org.junit.jupiter.api.Test;

/** 004 T008: IP당 1분 고정 창(댓글 5개, 방명록 3개), 넘으면 429 {@code TOO_MANY_REQUESTS} + Retry-After. */
class RateLimitGuestWriteGuardTest {

    private long nanos;
    private final RateLimitGuestWriteGuard guard = new RateLimitGuestWriteGuard(GuestProperties.defaults(),
            () -> nanos);

    private void allow(GuestWriteKind kind, String ip, int times) {
        for (int i = 0; i < times; i++) {
            assertThatCode(() -> guard.check(kind, ip)).doesNotThrowAnyException();
        }
    }

    @Test
    void commentLimitIsFivePerMinute() {
        allow(GuestWriteKind.COMMENT, "1.1.1.1", 5);
        nanos += TimeUnit.SECONDS.toNanos(20);
        assertThatThrownBy(() -> guard.check(GuestWriteKind.COMMENT, "1.1.1.1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
                    assertThat(e.retryAfterSeconds()).isEqualTo(40L);
                });
    }

    @Test
    void guestbookLimitIsThreePerMinuteAndSeparateFromComments() {
        allow(GuestWriteKind.GUESTBOOK, "1.1.1.1", 3);
        assertCode(() -> guard.check(GuestWriteKind.GUESTBOOK, "1.1.1.1"), ErrorCode.TOO_MANY_REQUESTS);
        allow(GuestWriteKind.COMMENT, "1.1.1.1", 5);
    }

    @Test
    void newWindowAfterOneMinute() {
        allow(GuestWriteKind.GUESTBOOK, "1.1.1.1", 3);
        nanos += TimeUnit.MINUTES.toNanos(1);
        allow(GuestWriteKind.GUESTBOOK, "1.1.1.1", 3);
    }

    @Test
    void ipsAreCountedSeparately() {
        allow(GuestWriteKind.GUESTBOOK, "1.1.1.1", 3);
        allow(GuestWriteKind.GUESTBOOK, "2.2.2.2", 3);
        allow(GuestWriteKind.GUESTBOOK, null, 3);
    }

    @Test
    void limitsComeFromProperties() {
        RateLimitGuestWriteGuard wide = new RateLimitGuestWriteGuard(
                new GuestProperties(1000, 1000, Duration.ofDays(90)), () -> nanos);
        for (int i = 0; i < 1000; i++) {
            wide.check(GuestWriteKind.GUESTBOOK, "1.1.1.1");
        }
        assertCode(() -> wide.check(GuestWriteKind.GUESTBOOK, "1.1.1.1"), ErrorCode.TOO_MANY_REQUESTS);
    }
}
