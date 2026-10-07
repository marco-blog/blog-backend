package net.java21.blog.backend.guest.service;

import static org.mockito.Mockito.verify;

import net.java21.blog.backend.guest.dto.GuestWriteKind;
import net.java21.blog.backend.spam.RateLimitKind;
import net.java21.blog.backend.spam.RateLimitPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 004 T008 → 005 T021: 비회원 쓰기 한도는 공용 {@link RateLimitPolicy}(ratelimit.*)로 IP별로 센다. */
@ExtendWith(MockitoExtension.class)
class RateLimitGuestWriteGuardTest {

    @Mock
    private RateLimitPolicy policy;

    @Test
    void delegatesToSharedPolicyPerIp() {
        RateLimitGuestWriteGuard guard = new RateLimitGuestWriteGuard(policy);

        guard.check(GuestWriteKind.COMMENT, "1.1.1.1");
        guard.check(GuestWriteKind.GUESTBOOK, "2.2.2.2");
        guard.check(GuestWriteKind.GUESTBOOK, null);

        verify(policy).check(RateLimitKind.COMMENT, "ip:1.1.1.1");
        verify(policy).check(RateLimitKind.GUESTBOOK, "ip:2.2.2.2");
        verify(policy).check(RateLimitKind.GUESTBOOK, "ip:");
    }
}
