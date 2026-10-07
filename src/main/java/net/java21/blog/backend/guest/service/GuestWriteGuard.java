package net.java21.blog.backend.guest.service;

import net.java21.blog.backend.guest.dto.GuestWriteKind;

/**
 * 비회원 쓰기 보호(004 research B6). 004는 IP당 속도 제한({@link RateLimitGuestWriteGuard})만 하고, 005가 CAPTCHA·회원 속도 제한·운영자
 * 설정값(005 FR-141·142)으로 이 구현을 바꾼다. 막아야 하면 {@code BusinessException}(429 {@code TOO_MANY_REQUESTS} 등)을 던진다.
 */
public interface GuestWriteGuard {

    void check(GuestWriteKind kind, String ip);
}
