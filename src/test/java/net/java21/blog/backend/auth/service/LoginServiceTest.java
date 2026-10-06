package net.java21.blog.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.auth.dto.LoginRequest;
import net.java21.blog.backend.blog.dto.BlogLink;
import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 로그인과 잠금(T051, FR-004, FR-007, FR-009, R12). */
@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    private static final AuthProperties PROPS = new AuthProperties(Duration.ofMinutes(30), Duration.ofHours(4),
            Duration.ofDays(7), Duration.ofSeconds(10), "test-only-jwt-secret-not-for-production-0000", 5,
            Duration.ofMinutes(10));

    @Mock
    private UserRepository userRepository;
    @Mock
    private BlogQueryRepository blogQueryRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private RefreshTokenService refreshTokenService;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-06T04:00:00Z"));
    private LoginService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new LoginService(userRepository, blogQueryRepository, TestEntities.HASHER, passwordEncoder,
                refreshTokenService, PROPS, clock);
        user = TestEntities.user(7L, "marco@example.com", "$2a$hash", "마르코");
    }

    private void userExists() {
        when(userRepository.findByEmailHash(TestEntities.HASHER.hashEmail("marco@example.com")))
                .thenReturn(Optional.of(user));
    }

    @Test
    void successReturnsMemberAndBlogsInCreationOrderAndResetsFailures() {
        userExists();
        TestEntities.with(user, "failedLoginCount", 3);
        when(passwordEncoder.matches("password1", "$2a$hash")).thenReturn(true);
        AuthTokens tokens = new AuthTokens("a", Duration.ofMinutes(30), "r", Duration.ofHours(4));
        when(refreshTokenService.startSession(user)).thenReturn(tokens);
        List<BlogLink> blogs = List.of(new BlogLink("marco", "첫 블로그"), new BlogLink("marco-dev", "둘째"));
        when(blogQueryRepository.findActiveBlogLinks(7L)).thenReturn(blogs);

        LoginService.Result result = service.login(new LoginRequest(" MARCO@example.com ", "password1"));

        assertThat(result.response().userId()).isEqualTo(7L);
        assertThat(result.response().nickname()).isEqualTo("마르코");
        assertThat(result.response().role()).isEqualTo("USER");
        assertThat(result.response().blogs()).containsExactlyElementsOf(blogs);
        assertThat(result.tokens()).isSameAs(tokens);
        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void unknownEmailAndWrongPasswordGiveTheSameError() {
        when(userRepository.findByEmailHash(anyString())).thenReturn(Optional.empty());
        expect(new LoginRequest("nobody@example.com", "password1"), ErrorCode.INVALID_CREDENTIALS);

        userExists();
        when(passwordEncoder.matches("wrong1234", "$2a$hash")).thenReturn(false);
        expect(new LoginRequest("marco@example.com", "wrong1234"), ErrorCode.INVALID_CREDENTIALS);
        assertThat(user.getFailedLoginCount()).isEqualTo(1);
        verify(refreshTokenService, never()).startSession(any());
    }

    @Test
    void fiveConsecutiveFailuresLockForTenMinutes() {
        userExists();
        when(passwordEncoder.matches(eq("wrong1234"), anyString())).thenReturn(false);
        for (int i = 1; i <= 4; i++) {
            expect(new LoginRequest("marco@example.com", "wrong1234"), ErrorCode.INVALID_CREDENTIALS);
        }
        expect(new LoginRequest("marco@example.com", "wrong1234"), ErrorCode.ACCOUNT_LOCKED);
        assertThat(user.getLockedUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));

        // 잠금 중에는 맞는 비밀번호도 거부한다.
        clock.advance(Duration.ofMinutes(9));
        expect(new LoginRequest("marco@example.com", "password1"), ErrorCode.ACCOUNT_LOCKED);
        verify(passwordEncoder, never()).matches(eq("password1"), anyString());

        // 10분이 지나면 다시 로그인할 수 있다.
        clock.advance(Duration.ofMinutes(1));
        when(passwordEncoder.matches("password1", "$2a$hash")).thenReturn(true);
        service.login(new LoginRequest("marco@example.com", "password1"));
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getFailedLoginCount()).isZero();
    }

    @Test
    void successInBetweenResetsTheConsecutiveCount() {
        userExists();
        when(passwordEncoder.matches("wrong1234", "$2a$hash")).thenReturn(false);
        when(passwordEncoder.matches("password1", "$2a$hash")).thenReturn(true);
        for (int i = 0; i < 4; i++) {
            expect(new LoginRequest("marco@example.com", "wrong1234"), ErrorCode.INVALID_CREDENTIALS);
        }
        service.login(new LoginRequest("marco@example.com", "password1"));
        expect(new LoginRequest("marco@example.com", "wrong1234"), ErrorCode.INVALID_CREDENTIALS);
        assertThat(user.getFailedLoginCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void suspendedAndWithdrawnMembersCannotLogIn(UserStatus status) {
        userExists();
        TestEntities.with(user, "status", status);
        expect(new LoginRequest("marco@example.com", "password1"), ErrorCode.INVALID_CREDENTIALS);
        verify(refreshTokenService, never()).startSession(any());
    }

    private void expect(LoginRequest request, ErrorCode code) {
        assertThatThrownBy(() -> service.login(request))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
