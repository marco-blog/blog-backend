package net.java21.blog.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import net.java21.blog.backend.auth.domain.RefreshToken;
import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.security.AuthUser;
import net.java21.blog.backend.security.JwtProvider;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 리프레시 토큰 회전·재사용 감지·유예·로그아웃(T052, FR-005·006, R2, AS4·5, quickstart #8).
 * 저장소는 해시 → 토큰 지도로 흉내 내고, 폐기 UPDATE는 지도의 같은 계열에 반영한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RefreshTokenServiceTest {

    private static final AuthProperties PROPS = new AuthProperties(Duration.ofMinutes(30), Duration.ofHours(4),
            Duration.ofDays(7), Duration.ofSeconds(10), "test-only-jwt-secret-not-for-production-0000", 5,
            Duration.ofMinutes(10));

    @Mock
    private RefreshTokenRepository repository;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
    private final Map<String, RefreshToken> byHash = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private JwtProvider jwtProvider;
    private RefreshTokenService service;
    private User user;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(PROPS, clock);
        service = new RefreshTokenService(repository, jwtProvider, PROPS, clock);
        user = TestEntities.user(7L);
        when(repository.save(any(RefreshToken.class))).thenAnswer(inv -> {
            RefreshToken token = inv.getArgument(0);
            ReflectionTestUtils.setField(token, "id", ids.incrementAndGet());
            byHash.put(token.getTokenHash(), token);
            return token;
        });
        when(repository.findByTokenHashForUpdate(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byHash.get(inv.<String>getArgument(0))));
        when(repository.findByTokenHash(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(byHash.get(inv.<String>getArgument(0))));
        when(repository.revokeFamily(anyString(), any(Instant.class))).thenAnswer(inv -> {
            String familyId = inv.getArgument(0);
            Instant now = inv.getArgument(1);
            int count = 0;
            for (RefreshToken token : byHash.values()) {
                if (token.getFamilyId().equals(familyId) && token.getRevokedAt() == null) {
                    ReflectionTestUtils.setField(token, "revokedAt", now);
                    count++;
                }
            }
            return count;
        });
    }

    @Test
    void startSessionStoresOnlyTheSha256OfARandom256BitToken() {
        AuthTokens tokens = service.startSession(user);

        assertThat(tokens.refreshToken()).hasSize(43).matches("[A-Za-z0-9_-]+");
        RefreshToken stored = byHash.values().iterator().next();
        assertThat(stored.getTokenHash()).isEqualTo(RefreshTokenService.sha256(tokens.refreshToken())).hasSize(64)
                .isNotEqualTo(tokens.refreshToken());
        assertThat(stored.getFamilyId()).hasSize(36);
        assertThat(stored.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(4)));
        assertThat(stored.getFamilyExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(tokens.refreshMaxAge()).isEqualTo(Duration.ofHours(4));
        assertThat(tokens.accessMaxAge()).isEqualTo(Duration.ofMinutes(30));

        AuthUser authUser = jwtProvider.verify(tokens.accessToken()).orElseThrow();
        assertThat(authUser.userId()).isEqualTo(7L);
        assertThat(authUser.role()).isEqualTo("USER");
        assertThat(authUser.familyId()).isEqualTo(stored.getFamilyId());
        assertThat(tokens.toString()).doesNotContain(tokens.refreshToken());

        assertThat(service.startSession(user).refreshToken()).isNotEqualTo(tokens.refreshToken());
    }

    @Test
    void rotateIssuesNewTokenInSameFamilyAndMarksOldUsed() {
        AuthTokens first = service.startSession(user);
        RefreshToken old = byHash.get(RefreshTokenService.sha256(first.refreshToken()));
        clock.advance(Duration.ofHours(1));

        AuthTokens second = service.rotate(first.refreshToken());

        RefreshToken next = byHash.get(RefreshTokenService.sha256(second.refreshToken()));
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());
        assertThat(next.getFamilyId()).isEqualTo(old.getFamilyId());
        assertThat(next.getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(4)));
        assertThat(next.getFamilyExpiresAt()).isEqualTo(old.getFamilyExpiresAt());
        assertThat(old.getUsedAt()).isEqualTo(clock.instant());
        assertThat(old.getReplacedById()).isEqualTo(next.getId());
        assertThat(jwtProvider.verify(second.accessToken()).orElseThrow().familyId()).isEqualTo(old.getFamilyId());
    }

    @Test
    void idleExpiryAfterFourHours() {
        AuthTokens first = service.startSession(user);
        clock.advance(Duration.ofHours(4));
        expectInvalid(first.refreshToken());
    }

    @Test
    void familyAbsoluteExpiryAfterSevenDaysEvenWhenRotatedRegularly() {
        AuthTokens tokens = service.startSession(user);
        for (int i = 0; i < 7 * 24 / 3; i++) {
            clock.advance(Duration.ofHours(3));
            if (clock.instant().isBefore(Instant.parse("2026-10-13T00:00:00Z"))) {
                tokens = service.rotate(tokens.refreshToken());
            }
        }
        // 마지막 토큰의 유휴 만료도 절대 만료를 넘지 않는다.
        RefreshToken last = byHash.get(RefreshTokenService.sha256(tokens.refreshToken()));
        assertThat(last.getExpiresAt()).isBeforeOrEqualTo(Instant.parse("2026-10-13T00:00:00Z"));
        clock.set(Instant.parse("2026-10-13T00:00:00Z"));
        expectInvalid(tokens.refreshToken());
    }

    @Test
    void reuseAfterGraceRevokesTheWholeFamily() {
        AuthTokens first = service.startSession(user);
        AuthTokens second = service.rotate(first.refreshToken());
        clock.advance(Duration.ofSeconds(11));

        expectInvalid(first.refreshToken());

        assertThat(byHash.values()).allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
        // 정상 사용자가 갖고 있던 최신 토큰도 더는 쓸 수 없다.
        expectInvalid(second.refreshToken());
    }

    @Test
    void sameTokenWithinGraceReturnsTheJustIssuedToken() {
        AuthTokens first = service.startSession(user);
        AuthTokens second = service.rotate(first.refreshToken());
        clock.advance(Duration.ofSeconds(9));

        AuthTokens again = service.rotate(first.refreshToken());

        assertThat(again.refreshToken()).isEqualTo(second.refreshToken());
        assertThat(jwtProvider.verify(again.accessToken())).isPresent();
        assertThat(byHash).hasSize(2);
        assertThat(byHash.values()).allSatisfy(t -> assertThat(t.getRevokedAt()).isNull());
    }

    @Test
    void withinGraceButUnknownToThisServerIsRejectedWithoutRevoking() {
        AuthTokens first = service.startSession(user);
        service.rotate(first.refreshToken());
        RefreshTokenService restarted = new RefreshTokenService(repository, jwtProvider, PROPS, clock);

        assertThatThrownBy(() -> restarted.rotate(first.refreshToken()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFRESH_INVALID));
        verify(repository, never()).revokeFamily(anyString(), any());
    }

    @Test
    void revokedTokenIsInvalid() {
        AuthTokens first = service.startSession(user);
        service.logout(first.refreshToken(), null);
        expectInvalid(first.refreshToken());
    }

    @Test
    void logoutRevokesFamilyThenRefreshIsInvalid() {
        AuthTokens first = service.startSession(user);
        AuthTokens second = service.rotate(first.refreshToken());
        AuthTokens otherDevice = service.startSession(user);

        service.logout(second.refreshToken(), null);

        expectInvalid(second.refreshToken());
        assertThat(service.rotate(otherDevice.refreshToken()).refreshToken()).isNotBlank();
    }

    @Test
    void logoutFallsBackToAccessTokenFamily() {
        AuthTokens tokens = service.startSession(user);
        String familyId = jwtProvider.verify(tokens.accessToken()).orElseThrow().familyId();

        service.logout(null, familyId);

        expectInvalid(tokens.refreshToken());
    }

    @Test
    void logoutWithNothingDoesNothing() {
        service.logout(null, null);
        service.logout("unknown-token", null);
        verify(repository, never()).revokeFamily(anyString(), any());
    }

    @Test
    void missingOrUnknownTokenIsInvalid() {
        expectInvalid(null);
        expectInvalid(" ");
        expectInvalid("not-a-token");
    }

    @Test
    void inactiveMemberCannotRefreshAndFamilyIsRevoked() {
        AuthTokens tokens = service.startSession(user);
        TestEntities.with(user, "status", UserStatus.SUSPENDED);

        expectInvalid(tokens.refreshToken());
        assertThat(byHash.values()).allSatisfy(t -> assertThat(t.getRevokedAt()).isNotNull());
    }

    private void expectInvalid(String token) {
        assertThatThrownBy(() -> service.rotate(token))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.REFRESH_INVALID));
    }
}
