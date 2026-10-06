package net.java21.blog.backend.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.service.MediaReferenceService;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.dto.UpdateMeRequest;
import net.java21.blog.backend.user.repository.UserRepository;
import net.java21.blog.backend.user.repository.WithdrawalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 프로필 수정과 탈퇴(T121, FR-008·009, contracts/api.md 회원 절).
 * PATCH /me는 보낸 필드만 바꾼다. 탈퇴는 비밀번호 확인 → WITHDRAWN·withdrawn_at, 모든 블로그의 모든 글 PRIVATE, 모든 family 폐기, 되돌리기 없음.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:00:00Z");

    @Mock
    private UserRepository userRepository;
    @Mock
    private WithdrawalRepository withdrawalRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private MediaReferenceService mediaReferences;

    private AccountService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AccountService(userRepository, withdrawalRepository, refreshTokenRepository, passwordEncoder,
                mediaReferences, new MutableClock(NOW));
        user = TestEntities.user(7L, "marco@example.com", "$2a$hash", "마르코");
    }

    private void userExists() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
    }

    private static UpdateMeRequest request() {
        return new UpdateMeRequest();
    }

    @Test
    void updatesOnlySentFields() {
        userExists();
        TestEntities.with(user, "bio", "원래 소개");
        UpdateMeRequest request = request();
        request.setNickname("  새 닉네임 ");

        service.updateProfile(7L, request);

        assertThat(user.getNickname()).isEqualTo("새 닉네임");
        assertThat(user.getBio()).isEqualTo("원래 소개");
        assertThat(user.getLocale()).isEqualTo("ko");
        assertThat(user.getTimeZone()).isEqualTo("Asia/Seoul");
    }

    @Test
    void updatesBioLocaleAndTimeZone() {
        userExists();
        UpdateMeRequest request = request();
        request.setBio("자바와 스프링");
        request.setLocale("zh-CN");
        request.setTimeZone("America/New_York");

        service.updateProfile(7L, request);

        assertThat(user.getBio()).isEqualTo("자바와 스프링");
        assertThat(user.getLocale()).isEqualTo("zh-CN");
        assertThat(user.getTimeZone()).isEqualTo("America/New_York");
    }

    @Test
    void blankOrNullBioAndNullLocaleClearValues() {
        userExists();
        TestEntities.with(user, "bio", "원래 소개");
        UpdateMeRequest request = request();
        request.setBio("   ");
        request.setLocale(null);

        service.updateProfile(7L, request);

        assertThat(user.getBio()).isNull();
        assertThat(user.getLocale()).isNull();
    }

    @Test
    void nicknameAndBioLengthLimits() {
        userExists();
        UpdateMeRequest request = request();
        request.setNickname("가".repeat(30));
        request.setBio("나".repeat(300));

        service.updateProfile(7L, request);

        assertThat(user.getNickname()).hasSize(30);
        assertThat(user.getBio()).hasSize(300);
    }

    @Test
    void invalidValuesAreValidationFailedWithAllFieldErrors() {
        userExists();
        UpdateMeRequest request = request();
        request.setNickname("가".repeat(31));
        request.setBio("나".repeat(301));
        request.setLocale("fr");
        request.setTimeZone("Mars/Base");

        assertThatThrownBy(() -> service.updateProfile(7L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).containsExactly(
                            new FieldError("nickname", "TOO_LONG", Map.of("max", 30)),
                            new FieldError("bio", "TOO_LONG", Map.of("max", 300)),
                            FieldError.of("locale", "INVALID"),
                            FieldError.of("timeZone", "INVALID"));
                });
        assertThat(user.getNickname()).isEqualTo("마르코");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankNicknameIsRequired(String nickname) {
        userExists();
        UpdateMeRequest request = request();
        request.setNickname(nickname);

        assertThatThrownBy(() -> service.updateProfile(7L, request))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.fieldErrors()).containsExactly(FieldError.of("nickname", "REQUIRED")));
    }

    @Test
    void nullNicknameAndTimeZoneAreRequired() {
        userExists();
        UpdateMeRequest request = request();
        request.setNickname(null);
        request.setTimeZone(null);

        assertThatThrownBy(() -> service.updateProfile(7L, request))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors()).containsExactly(
                        FieldError.of("nickname", "REQUIRED"), FieldError.of("timeZone", "REQUIRED")));
    }

    @Test
    void profileImageMustBeOwnProfileUpload() {
        userExists();
        UpdateMeRequest request = request();
        request.setProfileImageMediaKey("k3Jd9fQ2xLmA7pZ0bR5tYw");
        when(mediaReferences.findOwned(7L, "k3Jd9fQ2xLmA7pZ0bR5tYw", MediaPurpose.PROFILE)).thenReturn(null);

        assertThatThrownBy(() -> service.updateProfile(7L, request))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.fieldErrors()).containsExactly(FieldError.of("profileImageMediaKey", "INVALID")));
    }

    @Test
    void inactiveOrMissingMemberIsUnauthenticated() {
        when(userRepository.findById(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateProfile(7L, request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));

        TestEntities.with(user, "status", UserStatus.SUSPENDED);
        userExists();
        assertThatThrownBy(() -> service.updateProfile(7L, request()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }

    @Test
    void withdrawWithWrongPasswordChangesNothing() {
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        assertThatThrownBy(() -> service.withdraw(7L, "wrong"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_MISMATCH));

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(withdrawalRepository, never()).makeAllPostsPrivate(anyLong());
        verify(refreshTokenRepository, never()).revokeAllByUserId(anyLong(), any());
    }

    @Test
    void withdrawHidesAllPostsAndRevokesAllFamilies() {
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "$2a$hash")).thenReturn(true);

        service.withdraw(7L, "password1");

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        assertThat(user.getWithdrawnAt()).isEqualTo(NOW);
        assertThat(user.isActive()).isFalse();
        verify(withdrawalRepository).makeAllPostsPrivate(7L);
        verify(refreshTokenRepository).revokeAllByUserId(7L, NOW);
    }

    @Test
    void withdrawnMemberCannotWithdrawOrComeBack() {
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1", "$2a$hash")).thenReturn(true);
        service.withdraw(7L, "password1");

        assertThatThrownBy(() -> service.withdraw(7L, "password1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.updateProfile(7L, request()))
                .isInstanceOf(BusinessException.class);
        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    @Test
    void withdrawWithoutPasswordIsMismatch() {
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.withdraw(7L, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CURRENT_PASSWORD_MISMATCH));
    }
}
