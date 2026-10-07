package net.java21.blog.backend.guestbook.repository;

import java.time.Instant;

import net.java21.blog.backend.guestbook.domain.GuestbookStatus;

/**
 * 방명록 목록 한 줄(DTO projection). 회원 작성자(프로필 이미지 키 포함)는 같은 쿼리의 LEFT JOIN으로 읽고, 비회원이면
 * {@code userId} null·{@code guestName} 값이다. 비밀번호 해시와 IP는 읽지 않는다.
 */
public record GuestbookRow(Long id, Long parentId, String content, boolean secret, GuestbookStatus status, Long userId,
        String nickname, String profileMediaKey, String guestName, Instant createdAt, Instant updatedAt) {

    public boolean deleted() {
        return status != GuestbookStatus.ACTIVE;
    }
}
