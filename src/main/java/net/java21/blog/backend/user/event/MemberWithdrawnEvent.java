package net.java21.blog.backend.user.event;

import java.time.Instant;

/**
 * 회원 탈퇴(001 FR-009). {@code AccountService.withdraw}가 같은 트랜잭션 안에서 발행하고, 동기 리스너가 같은 트랜잭션에서 따라 바꾼다
 * (탈퇴가 롤백되면 함께 롤백, 007 FR-157).
 */
public record MemberWithdrawnEvent(long userId, Instant withdrawnAt) {
}
