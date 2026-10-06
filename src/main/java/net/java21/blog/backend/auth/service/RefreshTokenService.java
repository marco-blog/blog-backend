package net.java21.blog.backend.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.java21.blog.backend.auth.domain.RefreshToken;
import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.security.JwtProvider;
import net.java21.blog.backend.user.domain.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리프레시 토큰 발급·회전·폐기(FR-005·006, research R2).
 * <ul>
 *   <li>토큰은 무작위 256비트(Base64URL), DB에는 SHA-256(16진수 64자)만 저장한다.</li>
 *   <li>쓸 때마다 새 토큰을 발급하고 이전 토큰은 사용 처리({@code used_at}, {@code replaced_by_id})한다.</li>
 *   <li>유휴 만료 {@code refresh-idle-ttl}(4h), 로그인 계열 절대 만료 {@code refresh-absolute-ttl}(7d).</li>
 *   <li>이미 사용·폐기된 토큰이 다시 오면 계열 전체를 폐기한다(탈취 대응). 단, 사용 처리 후
 *       {@code refresh-reuse-grace}(10s) 안의 같은 토큰 재요청(여러 탭의 동시 리프레시)에는 방금 발급한 토큰을 돌려준다.
 *       DB에는 원문이 없으므로 방금 발급한 원문을 유예 시간 동안만 메모리에 둔다(서버 1대 기준. 재시작 등으로 없으면 401만 주고 폐기하지 않는다).</li>
 * </ul>
 * 실패해도 폐기는 남아야 하므로 {@code BusinessException}에는 롤백하지 않는다.
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final AuthProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    /** 사용 처리된 토큰 해시 → 그때 새로 발급한 원문(유예 시간 동안만). */
    private final Map<String, RecentRotation> recentRotations = new ConcurrentHashMap<>();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtProvider jwtProvider,
            AuthProperties properties, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProvider = jwtProvider;
        this.properties = properties;
        this.clock = clock;
    }

    /** 새 로그인 계열을 시작한다(로그인, 가입). */
    @Transactional
    public AuthTokens startSession(User user) {
        Instant now = clock.instant();
        String raw = newRawToken();
        RefreshToken token = RefreshToken.first(user, UUID.randomUUID().toString(), sha256(raw), now,
                properties.refreshIdleTtl(), properties.refreshAbsoluteTtl());
        refreshTokenRepository.save(token);
        return tokens(user, token, raw, now);
    }

    /** {@code POST /auth/refresh}: 회전. 실패는 모두 401 {@code REFRESH_INVALID}. */
    @Transactional(noRollbackFor = BusinessException.class)
    public AuthTokens rotate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw invalid("Missing refresh token");
        }
        String hash = sha256(rawToken);
        RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash)
                .orElseThrow(() -> invalid("Unknown refresh token"));
        Instant now = clock.instant();
        if (token.isRevoked()) {
            throw invalid("Revoked refresh token");
        }
        if (token.isUsed()) {
            return reuse(token, hash, now);
        }
        if (token.isExpired(now)) {
            throw invalid("Expired refresh token");
        }
        User user = token.getUser();
        if (!user.isActive()) {
            refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
            throw invalid("Member is not active");
        }
        String raw = newRawToken();
        RefreshToken next = refreshTokenRepository.save(token.next(sha256(raw), now, properties.refreshIdleTtl()));
        token.markUsed(now, next.getId());
        rememberRotation(hash, raw, next.getExpiresAt(), now);
        return tokens(user, next, raw, now);
    }

    /** 로그아웃: 리프레시 쿠키의 계열을, 없으면 접근 토큰의 계열({@code fid})을 폐기한다. 둘 다 없으면 아무것도 하지 않는다. */
    @Transactional
    public void logout(String rawRefreshToken, String accessFamilyId) {
        Instant now = clock.instant();
        String familyId = null;
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            familyId = refreshTokenRepository.findByTokenHash(sha256(rawRefreshToken))
                    .map(RefreshToken::getFamilyId)
                    .orElse(null);
        }
        if (familyId == null) {
            familyId = accessFamilyId;
        }
        if (familyId != null) {
            refreshTokenRepository.revokeFamily(familyId, now);
        }
    }

    private AuthTokens reuse(RefreshToken token, String hash, Instant now) {
        boolean withinGrace = now.isBefore(token.getUsedAt().plus(properties.refreshReuseGrace()));
        if (!withinGrace) {
            // 이미 교체된 토큰의 재사용: 탈취로 보고 계열 전체를 폐기한다.
            refreshTokenRepository.revokeFamily(token.getFamilyId(), now);
            throw invalid("Refresh token reuse detected");
        }
        RecentRotation recent = recentRotations.get(hash);
        if (recent == null || !now.isBefore(recent.graceUntil())) {
            throw invalid("Refresh token already rotated");
        }
        User user = token.getUser();
        String accessToken = jwtProvider.issue(user.getId(), user.getRole().name(), token.getFamilyId());
        return new AuthTokens(accessToken, jwtProvider.accessTtl(), recent.rawToken(),
                Duration.between(now, recent.expiresAt()));
    }

    private void rememberRotation(String usedHash, String newRaw, Instant newExpiresAt, Instant now) {
        recentRotations.values().removeIf(r -> !now.isBefore(r.graceUntil()));
        recentRotations.put(usedHash, new RecentRotation(newRaw, newExpiresAt, now.plus(properties.refreshReuseGrace())));
    }

    private AuthTokens tokens(User user, RefreshToken token, String raw, Instant now) {
        String accessToken = jwtProvider.issue(user.getId(), user.getRole().name(), token.getFamilyId());
        return new AuthTokens(accessToken, jwtProvider.accessTtl(), raw, Duration.between(now, token.getExpiresAt()));
    }

    private String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 16진수(소문자 64자). */
    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.REFRESH_INVALID, message);
    }

    private record RecentRotation(String rawToken, Instant expiresAt, Instant graceUntil) {
    }
}
