package net.java21.blog.backend.auth.service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import net.java21.blog.backend.auth.domain.PasswordResetToken;
import net.java21.blog.backend.auth.repository.PasswordResetTokenRepository;
import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.auth.validation.PasswordPolicy;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.mail.PasswordResetMail;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 재설정(FR-133, quickstart #19).
 * <ol>
 *   <li>요청: 활성 회원의 이메일이면 무작위 256비트 토큰을 만들어 SHA-256만 저장하고(30분), 메일 발송 이벤트를 낸다.
 *       가입되지 않았거나 활성이 아닌 이메일은 아무것도 하지 않는다. 어느 쪽이든 응답은 같다(가입 여부를 드러내지 않음).</li>
 *   <li>확인: 사용하지 않았고 만료 전인 토큰이면 새 비밀번호로 바꾸고 토큰을 사용 처리하고 모든 로그인 계열을 폐기한다.
 *       아니면 400 {@code PASSWORD_RESET_TOKEN_INVALID}.</li>
 * </ol>
 */
@Service
public class PasswordResetService {

    private static final int TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final PersonalDataHasher hasher;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(UserRepository userRepository, PasswordResetTokenRepository tokenRepository,
            RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder, PersonalDataHasher hasher,
            ApplicationEventPublisher events, Clock clock) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.hasher = hasher;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void request(String email) {
        User user = userRepository.findByEmailHash(hasher.hashEmail(email)).orElse(null);
        if (user == null || !user.isActive()) {
            return;
        }
        String raw = newRawToken();
        tokenRepository.save(PasswordResetToken.issue(user, RefreshTokenService.sha256(raw), clock.instant()));
        events.publishEvent(new PasswordResetMail(user.getId(), user.getEmail(), user.getLocale(), raw));
    }

    @Transactional
    public void confirm(String rawToken, String newPassword) {
        if (!PasswordPolicy.isAcceptable(newPassword)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of("newPassword", PasswordPolicy.CODE)));
        }
        if (rawToken == null || rawToken.isBlank()) {
            throw invalid();
        }
        Instant now = clock.instant();
        PasswordResetToken token = tokenRepository.findByTokenHashForUpdate(RefreshTokenService.sha256(rawToken))
                .filter(t -> t.isUsable(now))
                .orElseThrow(PasswordResetService::invalid);
        User user = token.getUser();
        if (!user.isActive()) {
            throw invalid();
        }
        token.markUsed(now);
        user.changePassword(passwordEncoder.encode(newPassword));
        refreshTokenRepository.revokeAllByUserId(user.getId(), now);
    }

    private String newRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.PASSWORD_RESET_TOKEN_INVALID, "Password reset token is invalid");
    }
}
