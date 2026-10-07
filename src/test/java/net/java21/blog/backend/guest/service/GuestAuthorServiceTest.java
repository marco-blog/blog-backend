package net.java21.blog.backend.guest.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.security.AttemptTarget;
import net.java21.blog.backend.common.security.PasswordAttemptGuard;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.guest.dto.GuestCredentials;
import net.java21.blog.backend.guest.dto.GuestWriteKind;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 004 T007: 비회원 쓰기 공통 규칙(research B6, FR-066). */
@ExtendWith(MockitoExtension.class)
class GuestAuthorServiceTest {

    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "Mozilla/5.0");
    private static final AttemptTarget TARGET = AttemptTarget.guestbook(9L);

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    @Mock
    private GuestWriteGuard writeGuard;
    @Mock
    private PasswordAttemptGuard attemptGuard;

    private GuestAuthorService service() {
        return new GuestAuthorService(encoder, writeGuard, attemptGuard);
    }

    @Test
    void guestWritingMustBeEnabledOnBlog() {
        Blog blog = TestEntities.blog(1L, TestEntities.user(1L), "marco");
        assertCode(() -> service().requireGuestAllowed(blog), ErrorCode.UNAUTHENTICATED);
        blog.changeGuestSettings(true, true);
        service().requireGuestAllowed(blog);
    }

    @Test
    void newGuestHashesPasswordKeepsIpAndChecksRate() {
        GuestCredentials guest = service().newGuest("  손님\u0007 ", "1234", CLIENT, GuestWriteKind.GUESTBOOK);

        assertThat(guest.name()).isEqualTo("손님");
        assertThat(guest.ip()).isEqualTo("203.0.113.7");
        assertThat(guest.passwordHash()).startsWith("$2").isNotEqualTo("1234");
        assertThat(encoder.matches("1234", guest.passwordHash())).isTrue();
        assertThat(guest.toString()).doesNotContain("203.0.113.7").doesNotContain(guest.passwordHash());
        verify(writeGuard).check(GuestWriteKind.GUESTBOOK, "203.0.113.7");
    }

    @Test
    void nameAndPasswordAreValidatedBeforeRateLimit() {
        assertFields("   ", "1234", "guestName", "REQUIRED");
        assertFields(null, "1234", "guestName", "REQUIRED");
        assertFields("가".repeat(31), "1234", "guestName", "TOO_LONG");
        assertFields("손님", null, "guestPassword", "REQUIRED");
        assertFields("손님", "", "guestPassword", "REQUIRED");
        assertFields("손님", "123", "guestPassword", "TOO_SHORT");
        assertFields("손님", "x".repeat(65), "guestPassword", "TOO_LONG");
        assertThat(service().newGuest("가".repeat(30), "x".repeat(64), CLIENT, GuestWriteKind.COMMENT).name())
                .hasSize(30);
        verify(writeGuard).check(GuestWriteKind.COMMENT, "203.0.113.7");
    }

    @Test
    void rateLimitRejectionPropagates() {
        doThrow(BusinessException.retryAfter(ErrorCode.TOO_MANY_REQUESTS, "slow", 30)).when(writeGuard)
                .check(any(), any());
        assertCode(() -> service().newGuest("손님", "1234", CLIENT, GuestWriteKind.COMMENT),
                ErrorCode.TOO_MANY_REQUESTS);
    }

    @Test
    void verifyMismatchRecordsFailure() {
        String hash = encoder.encode("1234");
        assertCode(() -> service().verify(hash, "9999", TARGET, "v:a", "1.1.1.1"), ErrorCode.GUEST_PASSWORD_MISMATCH);
        assertCode(() -> service().verify(hash, null, TARGET, "v:a", "1.1.1.1"), ErrorCode.GUEST_PASSWORD_MISMATCH);
        assertCode(() -> service().verify(null, "1234", TARGET, "v:a", "1.1.1.1"), ErrorCode.GUEST_PASSWORD_MISMATCH);
        verify(attemptGuard, org.mockito.Mockito.times(3)).recordFailure(TARGET, "v:a", "1.1.1.1");
        verify(attemptGuard, never()).recordSuccess(any(), any(), any());
    }

    @Test
    void verifyMatchRecordsSuccessAfterCheck() {
        String hash = encoder.encode("1234");
        service().verify(hash, "1234", TARGET, "v:a", "1.1.1.1");
        InOrder order = inOrder(attemptGuard);
        order.verify(attemptGuard).check(TARGET, "v:a", "1.1.1.1");
        order.verify(attemptGuard).recordSuccess(TARGET, "v:a", "1.1.1.1");
    }

    @Test
    void lockedTargetRejectsEvenCorrectPassword() {
        doThrow(BusinessException.retryAfter(ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED, "locked", 600)).when(attemptGuard)
                .check(TARGET, "v:a", "1.1.1.1");
        assertCode(() -> service().verify(encoder.encode("1234"), "1234", TARGET, "v:a", "1.1.1.1"),
                ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED);
        verify(attemptGuard, never()).recordSuccess(any(), any(), any());
    }

    private void assertFields(String name, String password, String field, String code) {
        assertThatThrownBy(() -> service().newGuest(name, password, CLIENT, GuestWriteKind.GUESTBOOK))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).anySatisfy(f -> {
                        assertThat(f.field()).isEqualTo(field);
                        assertThat(f.code()).isEqualTo(code);
                    });
                });
        verifyNoInteractions(writeGuard);
    }
}
