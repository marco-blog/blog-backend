package net.java21.blog.backend.user.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.auth.service.SignupService;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.service.MediaReferenceService;
import net.java21.blog.backend.spam.BannedWordMatcher;
import net.java21.blog.backend.subscription.repository.BlogSubscriptionRepository;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.dto.UpdateMeRequest;
import net.java21.blog.backend.user.repository.UserRepository;
import net.java21.blog.backend.user.repository.WithdrawalRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 설정: 프로필 수정과 탈퇴(FR-008, FR-009, FR-149, FR-153).
 * 탈퇴는 되돌릴 수 없다(복구 기능 없음). 30일 보존 뒤 개인정보는 {@code PrivacyPurgeJob}이 파기한다(FR-138).
 */
@Service
public class AccountService {

    private final UserRepository userRepository;
    private final WithdrawalRepository withdrawalRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MediaReferenceService mediaReferences;
    private final BlogSubscriptionRepository subscriptionRepository;
    private final BannedWordMatcher bannedWords;
    private final Clock clock;

    public AccountService(UserRepository userRepository, WithdrawalRepository withdrawalRepository,
            RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder,
            MediaReferenceService mediaReferences, BlogSubscriptionRepository subscriptionRepository,
            BannedWordMatcher bannedWords, Clock clock) {
        this.userRepository = userRepository;
        this.withdrawalRepository = withdrawalRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mediaReferences = mediaReferences;
        this.subscriptionRepository = subscriptionRepository;
        this.bannedWords = bannedWords;
        this.clock = clock;
    }

    /**
     * 보낸 필드만 바꾼다. 하나라도 틀리면 아무것도 바꾸지 않고 400 {@code VALIDATION_FAILED}(모든 입력란 오류를 함께).
     * 프로필 이미지는 {@code purpose=PROFILE}로 올린 본인 이미지만 받아 ATTACHED로 하고, 이전 이미지는 정리 대상 판단(FR-073).
     */
    @Transactional
    public void updateProfile(long userId, UpdateMeRequest request) {
        User user = requireActive(userRepository.findById(userId).orElse(null));
        List<FieldError> errors = new ArrayList<>();

        String nickname = null;
        if (request.hasNickname()) {
            nickname = request.getNickname() == null ? "" : request.getNickname().strip();
            if (nickname.isEmpty()) {
                errors.add(FieldError.of("nickname", "REQUIRED"));
            } else if (nickname.length() > User.NICKNAME_MAX) {
                errors.add(new FieldError("nickname", "TOO_LONG", Map.of("max", User.NICKNAME_MAX)));
            } else {
                bannedWords.collectName(errors, "nickname", nickname);
            }
        }
        String bio = null;
        if (request.hasBio()) {
            bio = request.getBio() == null || request.getBio().isBlank() ? null : request.getBio();
            if (bio != null && bio.length() > User.BIO_MAX) {
                errors.add(new FieldError("bio", "TOO_LONG", Map.of("max", User.BIO_MAX)));
            }
        }
        Media profileMedia = null;
        if (request.hasProfileImageMediaKey() && request.getProfileImageMediaKey() != null) {
            profileMedia = mediaReferences.findOwned(userId, request.getProfileImageMediaKey(), MediaPurpose.PROFILE);
            if (profileMedia == null) {
                errors.add(FieldError.of("profileImageMediaKey", "INVALID"));
            }
        }
        if (request.hasLocale() && request.getLocale() != null && !SignupService.LOCALES.contains(request.getLocale())) {
            errors.add(FieldError.of("locale", "INVALID"));
        }
        if (request.hasTimeZone()) {
            if (request.getTimeZone() == null) {
                errors.add(FieldError.of("timeZone", "REQUIRED"));
            } else if (!ZoneId.getAvailableZoneIds().contains(request.getTimeZone())) {
                errors.add(FieldError.of("timeZone", "INVALID"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", errors);
        }

        if (request.hasNickname()) {
            user.changeNickname(nickname);
        }
        if (request.hasBio()) {
            user.changeBio(bio);
        }
        if (request.hasLocale()) {
            user.changeLocale(request.getLocale());
        }
        if (request.hasTimeZone()) {
            user.changeTimeZone(request.getTimeZone());
        }
        if (request.hasProfileImageMediaKey()) {
            changeProfileMedia(user, profileMedia);
        }
    }

    private void changeProfileMedia(User user, Media media) {
        Long previousId = user.getProfileMediaId();
        if (media != null) {
            if (media.getId().equals(previousId)) {
                return;
            }
            mediaReferences.attach(media);
        }
        user.changeProfileMedia(media);
        if (previousId != null) {
            mediaReferences.reevaluate(List.of(previousId));
        }
    }

    /**
     * 탈퇴(FR-009): 비밀번호 확인 → {@code status=WITHDRAWN}·{@code withdrawn_at}, 모든 블로그의 모든 글 비공개
     * (이전 값은 보관하지 않음), 모든 로그인 계열 폐기. 회원 행을 잠가 블로그 만들기·삭제와 줄 세운다.
     * 002(결정 3): 같은 트랜잭션에서 이 회원의 구독 행을 지우고 구독했던 블로그들의 구독자 수를 줄인다(쿼리 2회, 블로그 수와 무관).
     * 좋아요 행과 좋아요 수는 그대로 둔다.
     */
    @Transactional
    public void withdraw(long userId, String password) {
        User user = requireActive(userRepository.findByIdForUpdate(userId).orElse(null));
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.CURRENT_PASSWORD_MISMATCH, "Password mismatch");
        }
        Instant now = clock.instant();
        user.withdraw(now);
        userRepository.flush();
        withdrawalRepository.makeAllPostsPrivate(userId);
        subscriptionRepository.decrementSubscriberCountsOf(userId);
        subscriptionRepository.deleteAllByUser(userId);
        refreshTokenRepository.revokeAllByUserId(userId, now);
    }

    private static User requireActive(User user) {
        if (user == null || !user.isActive()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Member is not active");
        }
        return user;
    }
}
