package net.java21.blog.backend.auth.service;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.java21.blog.backend.auth.dto.SignupRequest;
import net.java21.blog.backend.auth.dto.SignupResponse;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.HandlePolicy;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.legal.LegalProperties;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입(FR-001~003, FR-081): 회원과 첫 블로그를 한 트랜잭션에서 만들고 바로 로그인 계열을 시작한다.
 * 이메일은 소문자로 정규화해 암호화 저장하고, 중복은 {@code email_hash}(HMAC)로 판단한다(FR-134·135).
 */
@Service
public class SignupService {

    /** 화면 언어로 고를 수 있는 값(FR-149). */
    public static final Set<String> LOCALES = Set.of("ko", "en", "ja", "zh-CN");

    private final UserRepository userRepository;
    private final BlogRepository blogRepository;
    private final HandlePolicy handlePolicy;
    private final PersonalDataHasher hasher;
    private final PasswordEncoder passwordEncoder;
    private final LegalProperties legalProperties;
    private final RefreshTokenService refreshTokenService;
    private final Clock clock;

    public SignupService(UserRepository userRepository, BlogRepository blogRepository, HandlePolicy handlePolicy,
            PersonalDataHasher hasher, PasswordEncoder passwordEncoder, LegalProperties legalProperties,
            RefreshTokenService refreshTokenService, Clock clock) {
        this.userRepository = userRepository;
        this.blogRepository = blogRepository;
        this.handlePolicy = handlePolicy;
        this.hasher = hasher;
        this.passwordEncoder = passwordEncoder;
        this.legalProperties = legalProperties;
        this.refreshTokenService = refreshTokenService;
        this.clock = clock;
    }

    public record Result(SignupResponse response, AuthTokens tokens) {
    }

    @Transactional
    public Result signup(SignupRequest request) {
        validateAgreementsAndPreferences(request);
        if (!legalProperties.termsVersion().equals(request.termsVersion())) {
            throw new BusinessException(ErrorCode.TERMS_VERSION_OUTDATED, "Terms version is outdated");
        }
        handlePolicy.check(request.handle());

        String email = PersonalDataHasher.normalizeEmail(request.email());
        String emailHash = hasher.hash(email);
        if (userRepository.existsByEmailHash(emailHash)) {
            throw new BusinessException(ErrorCode.EMAIL_TAKEN, "Email taken");
        }
        if (blogRepository.existsByHandle(request.handle())) {
            throw new BusinessException(ErrorCode.HANDLE_TAKEN, "Handle taken");
        }

        String nickname = request.nickname().strip();
        User user = new User(email, emailHash, passwordEncoder.encode(request.password()), nickname,
                request.locale(), request.timeZone(), request.termsVersion(), clock.instant());
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.EMAIL_TAKEN, "Email taken");
        }
        Blog blog = new Blog(user, request.handle(), Blog.defaultTitle(nickname));
        try {
            blogRepository.saveAndFlush(blog);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.HANDLE_TAKEN, "Handle taken");
        }
        AuthTokens tokens = refreshTokenService.startSession(user);
        return new Result(new SignupResponse(user.getId(), blog.getHandle()), tokens);
    }

    private static void validateAgreementsAndPreferences(SignupRequest request) {
        List<FieldError> errors = new ArrayList<>();
        requireTrue(errors, "agreeTerms", request.agreeTerms());
        requireTrue(errors, "agreePrivacy", request.agreePrivacy());
        requireTrue(errors, "over14", request.over14());
        if (request.locale() != null && !LOCALES.contains(request.locale())) {
            errors.add(FieldError.of("locale", "INVALID"));
        }
        if (request.timeZone() != null && !ZoneId.getAvailableZoneIds().contains(request.timeZone())) {
            errors.add(FieldError.of("timeZone", "INVALID"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed", errors);
        }
    }

    private static void requireTrue(List<FieldError> errors, String field, Boolean value) {
        if (!Boolean.TRUE.equals(value)) {
            errors.add(FieldError.of(field, "REQUIRED"));
        }
    }
}
