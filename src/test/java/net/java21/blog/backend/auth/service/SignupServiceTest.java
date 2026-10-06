package net.java21.blog.backend.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import net.java21.blog.backend.auth.dto.SignupRequest;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.HandlePolicy;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.legal.LegalProperties;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/** 가입(T050, FR-001~003, FR-081, AS1·2·14). */
@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final String TERMS = "2026-10-06";

    @Mock
    private UserRepository userRepository;
    @Mock
    private BlogRepository blogRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private RefreshTokenService refreshTokenService;

    private SignupService service;

    @BeforeEach
    void setUp() {
        service = new SignupService(userRepository, blogRepository, new HandlePolicy(), TestEntities.HASHER,
                passwordEncoder, new LegalProperties(TERMS), refreshTokenService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SignupRequest request(String email, String handle) {
        return new SignupRequest(email, "password1", " 마르코 ", handle, true, true, true, TERMS, "en", "Europe/Paris");
    }

    @Test
    void createsUserAndFirstBlogAndStartsSession() {
        when(passwordEncoder.encode("password1")).thenReturn("$2a$bcrypt");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 42L);
            return user;
        });
        AuthTokens tokens = new AuthTokens("access", Duration.ofMinutes(30), "refresh", Duration.ofHours(4));
        when(refreshTokenService.startSession(any(User.class))).thenReturn(tokens);

        SignupService.Result result = service.signup(request("  Marco@Example.COM ", "marco"));

        assertThat(result.response().userId()).isEqualTo(42L);
        assertThat(result.response().handle()).isEqualTo("marco");
        assertThat(result.tokens()).isSameAs(tokens);

        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(user.capture());
        assertThat(user.getValue().getEmail()).isEqualTo("marco@example.com");
        assertThat(user.getValue().getEmailHash())
                .isEqualTo(TestEntities.HASHER.hash("marco@example.com"))
                .hasSize(64);
        assertThat(user.getValue().getPasswordHash()).isEqualTo("$2a$bcrypt");
        assertThat(user.getValue().getNickname()).isEqualTo("마르코");
        assertThat(user.getValue().getTermsVersion()).isEqualTo(TERMS);
        assertThat(user.getValue().getTermsAgreedAt()).isEqualTo(NOW);
        assertThat(user.getValue().getLocale()).isEqualTo("en");
        assertThat(user.getValue().getTimeZone()).isEqualTo("Europe/Paris");
        assertThat(user.getValue().getStatus().name()).isEqualTo("ACTIVE");
        assertThat(user.getValue().getRole().name()).isEqualTo("USER");

        ArgumentCaptor<Blog> blog = ArgumentCaptor.forClass(Blog.class);
        verify(blogRepository).saveAndFlush(blog.capture());
        assertThat(blog.getValue().getHandle()).isEqualTo("marco");
        assertThat(blog.getValue().getTitle()).isEqualTo("마르코의 블로그");
        assertThat(blog.getValue().getUser()).isSameAs(user.getValue());

        InOrder order = inOrder(userRepository, blogRepository, refreshTokenService);
        order.verify(userRepository).saveAndFlush(any());
        order.verify(blogRepository).saveAndFlush(any());
        order.verify(refreshTokenService).startSession(user.getValue());
    }

    @Test
    void localeAndTimeZoneAreOptional() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> TestEntities.with(inv.getArgument(0), "id", 1L));
        service.signup(new SignupRequest("a@b.com", "password1", "nick", "nick-blog", true, true, true, TERMS, null,
                null));
        ArgumentCaptor<User> user = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(user.capture());
        assertThat(user.getValue().getLocale()).isNull();
        assertThat(user.getValue().getTimeZone()).isEqualTo("Asia/Seoul");
    }

    @Test
    void duplicateEmailIsCheckedByNormalizedHash() {
        when(userRepository.existsByEmailHash(TestEntities.HASHER.hash("marco@example.com"))).thenReturn(true);

        expect(() -> service.signup(request("MARCO@example.com", "marco")), ErrorCode.EMAIL_TAKEN);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateHandleIncludingDeletedBlogsIsTaken() {
        when(blogRepository.existsByHandle("marco")).thenReturn(true);

        expect(() -> service.signup(request("marco@example.com", "marco")), ErrorCode.HANDLE_TAKEN);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void uniqueViolationsRacingWithOtherSignupsAreMappedToTakenCodes() {
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));
        expect(() -> service.signup(request("marco@example.com", "marco")), ErrorCode.EMAIL_TAKEN);
    }

    @Test
    void handleUniqueViolationIsHandleTaken() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> TestEntities.with(inv.getArgument(0), "id", 1L));
        when(blogRepository.saveAndFlush(any(Blog.class))).thenThrow(new DataIntegrityViolationException("dup"));
        expect(() -> service.signup(request("marco@example.com", "marco")), ErrorCode.HANDLE_TAKEN);
    }

    @Test
    void everyAgreementMustBeTrue() {
        SignupRequest request = new SignupRequest("a@b.com", "password1", "nick", "marco", false, true, false, TERMS,
                null, null);
        assertThatThrownBy(() -> service.signup(request))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("agreeTerms", "over14");
                    assertThat(e.fieldErrors()).extracting(FieldError::code).containsOnly("REQUIRED");
                });
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void privacyAgreementIsRequired() {
        expect(() -> service.signup(new SignupRequest("a@b.com", "password1", "nick", "marco", true, false, true,
                TERMS, null, null)), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void localeMustBeSupportedAndTimeZoneMustBeIana() {
        assertThatThrownBy(() -> service.signup(new SignupRequest("a@b.com", "password1", "nick", "marco", true, true,
                true, TERMS, "fr", "Mars/Olympus")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("locale", "timeZone");
                });
    }

    @Test
    void outdatedTermsVersionIs422() {
        expect(() -> service.signup(new SignupRequest("a@b.com", "password1", "nick", "marco", true, true, true,
                "2025-01-01", null, null)), ErrorCode.TERMS_VERSION_OUTDATED);
        verify(userRepository, never()).existsByEmailHash(anyString());
    }

    @Test
    void handleRulesAreChecked() {
        expect(() -> service.signup(request("a@b.com", "admin")), ErrorCode.HANDLE_RESERVED);
        expect(() -> service.signup(request("a@b.com", "a--b")), ErrorCode.HANDLE_INVALID);
    }

    private static void expect(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
