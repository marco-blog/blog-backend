package net.java21.blog.backend.guest.service;

import net.java21.blog.backend.guest.dto.GuestWriteKind;
import net.java21.blog.backend.spam.RateLimitKind;
import net.java21.blog.backend.spam.RateLimitPolicy;
import org.springframework.stereotype.Component;

/**
 * 비회원 쓰기 속도 제한(004 research B6). 005부터 공용 {@link RateLimitPolicy}를 쓰고 한도는 운영 설정
 * {@code ratelimit.comment-per-minute}(5)·{@code ratelimit.guestbook-per-minute}(3)(005 research M8). 비회원은 IP별로 센다.
 * 넘으면 429 {@code TOO_MANY_REQUESTS} + {@code Retry-After}. US2(T075)에서 회원·비회원 공용 {@code WriteGuard}로 대체한다.
 */
@Component
public class RateLimitGuestWriteGuard implements GuestWriteGuard {

    private final RateLimitPolicy policy;

    public RateLimitGuestWriteGuard(RateLimitPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void check(GuestWriteKind kind, String ip) {
        policy.check(kind == GuestWriteKind.COMMENT ? RateLimitKind.COMMENT : RateLimitKind.GUESTBOOK,
                "ip:" + (ip == null ? "" : ip));
    }
}
