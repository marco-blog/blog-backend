package net.java21.blog.backend.user.dto;

import java.time.Instant;

/**
 * {@code GET /me/login-history}의 한 줄(contracts/api.md 회원 절, FR-139).
 *
 * @param at       시도 시각(UTC)
 * @param ipMasked 일부를 가린 IP(IPv4 {@code 211.234.*.*}, IPv6 {@code 2001:db8:85a3::*}), 기록이 없으면 null
 * @param device   기기 정보(User-Agent 원문, 300자까지)
 */
public record LoginHistoryResponse(Instant at, boolean success, String ipMasked, String device) {
}
