package net.java21.blog.backend.auth.service;

import java.time.Clock;
import java.time.Instant;

import net.java21.blog.backend.auth.dto.LoginRequest;
import net.java21.blog.backend.auth.dto.LoginResponse;
import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.spam.captcha.LoginCaptchaPolicy;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import net.java21.blog.backend.user.service.LoginHistoryService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인(FR-004, FR-007, research R12).
 * <ul>
 *   <li>없는 이메일·틀린 비밀번호·정지·탈퇴 회원은 모두 401 {@code INVALID_CREDENTIALS}(어느 쪽인지 밝히지 않음).</li>
 *   <li>연속 {@code login-max-failures}(5)번 틀리면 {@code login-lock-duration}(10분) 동안 423 {@code ACCOUNT_LOCKED}.</li>
 *   <li>성공하면 실패 횟수를 0으로 되돌리고 새 로그인 계열을 시작한다.</li>
 *   <li>성공·실패마다 로그인 기록을 남긴다(FR-139). 없는 이메일은 회원 없이 남긴다.</li>
 *   <li>005: 같은 이메일 또는 같은 IP가 연속으로 실패했으면 비밀번호를 확인하기 전에 CAPTCHA를 요구한다({@link LoginCaptchaPolicy},
 *       400 {@code CAPTCHA_REQUIRED}·{@code CAPTCHA_FAILED} — 이때는 실패 수·로그인 기록을 늘리지 않는다).</li>
 * </ul>
 * 실패 횟수·잠금·로그인 기록은 오류를 던져도 저장해야 하므로 {@code BusinessException}에는 롤백하지 않는다.
 */
@Service
public class LoginService {

    /** 없는 이메일에도 BCrypt 비교를 한 번 해서 응답 시간으로 가입 여부가 드러나지 않게 한다. */
    private static final String DUMMY_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhi5BWX4Z3pV0fZQ1wUu8d6x5O2bW5w6";

    private final UserRepository userRepository;
    private final BlogQueryRepository blogQueryRepository;
    private final PersonalDataHasher hasher;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final LoginHistoryService loginHistoryService;
    private final AuthProperties authProperties;
    private final LoginCaptchaPolicy captchaPolicy;
    private final Clock clock;

    public LoginService(UserRepository userRepository, BlogQueryRepository blogQueryRepository,
            PersonalDataHasher hasher, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokenService,
            LoginHistoryService loginHistoryService, AuthProperties authProperties, LoginCaptchaPolicy captchaPolicy,
            Clock clock) {
        this.userRepository = userRepository;
        this.blogQueryRepository = blogQueryRepository;
        this.hasher = hasher;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.loginHistoryService = loginHistoryService;
        this.authProperties = authProperties;
        this.captchaPolicy = captchaPolicy;
        this.clock = clock;
    }

    public record Result(LoginResponse response, AuthTokens tokens) {
    }

    /**
     * @param client 방문자 주소·기기 정보(로그인 기록용)
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Result login(ClientInfo client, LoginRequest request) {
        String emailHash = hasher.hashEmail(request.email());
        String ip = client == null ? null : client.ip();
        captchaPolicy.requireIfNeeded(emailHash, ip, request.captchaToken());
        User user = userRepository.findByEmailHash(emailHash).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            loginHistoryService.record(null, false, client);
            captchaPolicy.recordFailure(emailHash, ip);
            throw invalidCredentials();
        }
        if (!user.isActive()) {
            loginHistoryService.record(user, false, client);
            captchaPolicy.recordFailure(emailHash, ip);
            throw invalidCredentials();
        }
        Instant now = clock.instant();
        if (user.isLocked(now)) {
            loginHistoryService.record(user, false, client);
            captchaPolicy.recordFailure(emailHash, ip);
            throw locked();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            boolean lockedNow = user.recordLoginFailure(authProperties.loginMaxFailures(),
                    authProperties.loginLockDuration(), now);
            loginHistoryService.record(user, false, client);
            captchaPolicy.recordFailure(emailHash, ip);
            throw lockedNow ? locked() : invalidCredentials();
        }
        captchaPolicy.reset(emailHash, ip);
        user.recordLoginSuccess();
        loginHistoryService.record(user, true, client);
        AuthTokens tokens = refreshTokenService.startSession(user);
        LoginResponse response = new LoginResponse(user.getId(), user.getNickname(), user.getRole().name(),
                blogQueryRepository.findActiveBlogLinks(user.getId()));
        return new Result(response, tokens);
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
    }

    private static BusinessException locked() {
        return new BusinessException(ErrorCode.ACCOUNT_LOCKED, "Account locked");
    }
}
