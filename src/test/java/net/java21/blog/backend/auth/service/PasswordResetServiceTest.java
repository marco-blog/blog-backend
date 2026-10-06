package net.java21.blog.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import net.java21.blog.backend.auth.domain.PasswordResetToken;
import net.java21.blog.backend.auth.repository.PasswordResetTokenRepository;
import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.mail.PasswordResetMail;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 재설정(T123, FR-133, quickstart #19).
 * <ul>
 *   <li>가입되지 않은 이메일도 요청은 똑같이 끝난다(컨트롤러는 항상 202).</li>
 *   <li>토큰 원문은 메일로만 나가고 DB에는 SHA-256만, 30분 만료, 한 번만 사용(아니면 400 {@code PASSWORD_RESET_TOKEN_INVALID}).</li>
 *   <li>재설정하면 모든 로그인 계열 폐기.</li>
 *   <li>메일은 이벤트로 넘겨 커밋 후 비동기로 회원 {@code locale} 언어로 보낸다({@code MailService}).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private ApplicationEventPublisher events;

    private final MutableClock clock = new MutableClock(NOW);
    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordResetService(userRepository, tokenRepository, refreshTokenRepository, passwordEncoder,
                TestEntities.HASHER, events, clock);
        user = TestEntities.user(7L, "marco@example.com", "$2a$old", "마르코");
        TestEntities.with(user, "locale", "ja");
    }

    @Test
    void unknownEmailDoesNothingVisible() {
        when(userRepository.findByEmailHash(TestEntities.HASHER.hashEmail("nobody@example.com")))
                .thenReturn(Optional.empty());

        service.request("nobody@example.com");

        verifyNoInteractions(tokenRepository, events);
    }

    @Test
    void inactiveMemberGetsNoMail() {
        TestEntities.with(user, "status", UserStatus.WITHDRAWN);
        when(userRepository.findByEmailHash(TestEntities.HASHER.hashEmail("marco@example.com")))
                .thenReturn(Optional.of(user));

        service.request("marco@example.com");

        verifyNoInteractions(tokenRepository, events);
    }

    @Test
    void requestStoresOnlyHashForThirtyMinutesAndPublishesMailInMemberLocale() {
        when(userRepository.findByEmailHash(TestEntities.HASHER.hashEmail("marco@example.com")))
                .thenReturn(Optional.of(user));

        service.request("  MARCO@example.com ");

        ArgumentCaptor<PasswordResetToken> token = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(token.capture());
        ArgumentCaptor<PasswordResetMail> mail = ArgumentCaptor.forClass(PasswordResetMail.class);
        verify(events).publishEvent(mail.capture());

        PasswordResetMail sent = mail.getValue();
        assertThat(sent.userId()).isEqualTo(7L);
        assertThat(sent.to()).isEqualTo("marco@example.com");
        assertThat(sent.locale()).isEqualTo("ja");
        assertThat(sent.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(sent.toString()).doesNotContain(sent.token()).doesNotContain("marco@example.com");

        PasswordResetToken saved = token.getValue();
        assertThat(saved.getUser()).isSameAs(user);
        assertThat(saved.getTokenHash()).isEqualTo(RefreshTokenService.sha256(sent.token())).hasSize(64);
        assertThat(saved.getTokenHash()).doesNotContain(sent.token());
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(saved.getUsedAt()).isNull();
    }

    @Test
    void eachRequestIssuesDifferentToken() {
        when(userRepository.findByEmailHash(any())).thenReturn(Optional.of(user));

        service.request("marco@example.com");
        service.request("marco@example.com");

        ArgumentCaptor<PasswordResetMail> mail = ArgumentCaptor.forClass(PasswordResetMail.class);
        verify(events, org.mockito.Mockito.times(2)).publishEvent(mail.capture());
        assertThat(mail.getAllValues().get(0).token()).isNotEqualTo(mail.getAllValues().get(1).token());
    }

    private PasswordResetToken stored(Instant issuedAt) {
        PasswordResetToken token = PasswordResetToken.issue(user, RefreshTokenService.sha256("raw-token"), issuedAt);
        when(tokenRepository.findByTokenHashForUpdate(RefreshTokenService.sha256("raw-token")))
                .thenReturn(Optional.of(token));
        return token;
    }

    @Test
    void confirmSetsPasswordUsesTokenAndRevokesEveryFamily() {
        PasswordResetToken token = stored(NOW.minus(Duration.ofMinutes(29)));
        when(passwordEncoder.encode("newPassword1")).thenReturn("$2a$new");

        service.confirm("raw-token", "newPassword1");

        assertThat(user.getPasswordHash()).isEqualTo("$2a$new");
        assertThat(token.getUsedAt()).isEqualTo(NOW);
        verify(refreshTokenRepository).revokeAllByUserId(7L, NOW);
    }

    @Test
    void confirmClearsLoginLock() {
        stored(NOW);
        TestEntities.with(user, "lockedUntil", NOW.plusSeconds(600));
        TestEntities.with(user, "failedLoginCount", 3);
        when(passwordEncoder.encode("newPassword1")).thenReturn("$2a$new");

        service.confirm("raw-token", "newPassword1");

        assertThat(user.isLocked(NOW)).isFalse();
        assertThat(user.getFailedLoginCount()).isZero();
    }

    @Test
    void tokenCanBeUsedOnlyOnce() {
        stored(NOW);
        when(passwordEncoder.encode("newPassword1")).thenReturn("$2a$new");
        service.confirm("raw-token", "newPassword1");

        assertInvalid(() -> service.confirm("raw-token", "otherPassword2"));
        assertThat(user.getPasswordHash()).isEqualTo("$2a$new");
    }

    @Test
    void expiredTokenIsInvalid() {
        stored(NOW.minus(Duration.ofMinutes(30)));

        assertInvalid(() -> service.confirm("raw-token", "newPassword1"));
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void unknownTokenIsInvalid() {
        when(tokenRepository.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());

        assertInvalid(() -> service.confirm("raw-token", "newPassword1"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void blankTokenIsInvalid(String token) {
        assertInvalid(() -> service.confirm(token, "newPassword1"));
        verifyNoInteractions(tokenRepository);
    }

    @Test
    void tokenOfInactiveMemberIsInvalid() {
        stored(NOW);
        TestEntities.with(user, "status", UserStatus.SUSPENDED);

        assertInvalid(() -> service.confirm("raw-token", "newPassword1"));
    }

    @Test
    void newPasswordMustFollowPolicy() {
        assertThatThrownBy(() -> service.confirm("raw-token", "weak"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).containsExactly(FieldError.of("newPassword", "PASSWORD_WEAK"));
                });
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.PASSWORD_RESET_TOKEN_INVALID);
            assertThat(e.errorCode().status().value()).isEqualTo(400);
        });
    }
}
