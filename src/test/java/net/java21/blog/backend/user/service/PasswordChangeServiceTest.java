package net.java21.blog.backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 비밀번호 변경(T122, FR-082, quickstart #20): 현재 비밀번호 확인, 새 비밀번호 규칙,
 * 성공하면 현재 기기의 로그인 계열(접근 토큰의 {@code fid})만 남기고 모든 계열 폐기(tasks.md "구현 전 결정 사항" 2번).
 */
@ExtendWith(MockitoExtension.class)
class PasswordChangeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:00:00Z");
    private static final String FAMILY = "11111111-1111-1111-1111-111111111111";

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private PasswordChangeService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new PasswordChangeService(userRepository, refreshTokenRepository, passwordEncoder,
                new MutableClock(NOW));
        user = TestEntities.user(7L, "marco@example.com", "$2a$old", "마르코");
    }

    @Test
    void wrongCurrentPasswordIsMismatchAndKeepsSessions() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong1234", "$2a$old")).thenReturn(false);

        assertThatThrownBy(() -> service.change(7L, FAMILY, "wrong1234", "newPassword1"))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_MISMATCH);
                    assertThat(e.errorCode().status().value()).isEqualTo(400);
                });

        assertThat(user.getPasswordHash()).isEqualTo("$2a$old");
        verifyNoInteractions(refreshTokenRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short1", "onlyletters", "1234567890"})
    void newPasswordMustFollowPolicy(String weak) {
        assertThatThrownBy(() -> service.change(7L, FAMILY, "password1", weak))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).containsExactly(FieldError.of("newPassword", "PASSWORD_WEAK"));
                });
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void successChangesHashAndRevokesOtherFamilies() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "$2a$old")).thenReturn(true);
        when(passwordEncoder.encode("newPassword1")).thenReturn("$2a$new");

        service.change(7L, FAMILY, "password1", "newPassword1");

        assertThat(user.getPasswordHash()).isEqualTo("$2a$new");
        verify(refreshTokenRepository).revokeAllByUserIdExceptFamily(7L, FAMILY, NOW);
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any());
    }

    @Test
    void withoutCurrentFamilyEveryFamilyIsRevoked() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "$2a$old")).thenReturn(true);
        when(passwordEncoder.encode("newPassword1")).thenReturn("$2a$new");

        service.change(7L, null, "password1", "newPassword1");

        verify(refreshTokenRepository).revokeAllByUserId(7L, NOW);
    }

    @Test
    void inactiveMemberIsUnauthenticated() {
        TestEntities.with(user, "status", UserStatus.WITHDRAWN);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.change(7L, FAMILY, "password1", "newPassword1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
