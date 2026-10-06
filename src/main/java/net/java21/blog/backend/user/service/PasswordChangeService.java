package net.java21.blog.backend.user.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.auth.validation.PasswordPolicy;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 변경(FR-082, quickstart #20). 현재 비밀번호를 확인하고, 바꾸면 이 기기를 뺀 모든 기기의 로그인을 끊는다.
 * "이 기기"는 접근 토큰의 로그인 계열 ID({@code fid}, tasks.md "구현 전 결정 사항" 2번)로 안다.
 * 다른 기기의 접근 토큰은 수명(30분)까지 남지만 리프레시가 막혀 그 뒤로는 다시 로그인해야 한다.
 */
@Service
public class PasswordChangeService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public PasswordChangeService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder, Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /**
     * @param currentFamilyId 이 기기의 로그인 계열. 모르면(null) 모든 계열을 폐기한다
     */
    @Transactional
    public void change(long userId, String currentFamilyId, String currentPassword, String newPassword) {
        if (!PasswordPolicy.isAcceptable(newPassword)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(FieldError.of("newPassword", PasswordPolicy.CODE)));
        }
        User user = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Member is not active"));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH, "Current password mismatch");
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        Instant now = clock.instant();
        if (currentFamilyId == null) {
            refreshTokenRepository.revokeAllByUserId(userId, now);
        } else {
            refreshTokenRepository.revokeAllByUserIdExceptFamily(userId, currentFamilyId, now);
        }
    }
}
