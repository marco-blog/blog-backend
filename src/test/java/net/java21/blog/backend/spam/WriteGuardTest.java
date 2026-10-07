package net.java21.blog.backend.spam;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.spam.captcha.CaptchaVerifier;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 005 T066: 댓글·방명록 쓰기 검사 순서(CAPTCHA → 속도 → 금칙어 → 반복), 수정은 금칙어만, 관리자 제외(FR-141~144). */
@ExtendWith(MockitoExtension.class)
class WriteGuardTest {

    @Mock
    private CaptchaVerifier captcha;
    @Mock
    private RateLimitPolicy rateLimits;
    @Mock
    private BannedWordMatcher bannedWords;
    @Mock
    private DuplicateContentDetector duplicates;

    private WriteGuard guard;
    private User member;

    @BeforeEach
    void setUp() {
        guard = new WriteGuard(captcha, rateLimits, bannedWords, duplicates);
        member = TestEntities.user(7L);
    }

    @Test
    void guestPathRunsAllChecksInOrder() {
        when(bannedWords.filterContent("content", "hello")).thenReturn("h***o");
        String saved = guard.guardNew(WriteGuard.Kind.COMMENT, WriteGuard.Writer.guest("203.0.113.1"), "hello",
                "token", "손님");

        assertThat(saved).isEqualTo("h***o");
        InOrder order = inOrder(captcha, rateLimits, bannedWords, duplicates);
        order.verify(captcha).verify("token", "203.0.113.1");
        order.verify(rateLimits).check(RateLimitKind.COMMENT, "ip:203.0.113.1");
        order.verify(bannedWords).requireCleanName("guestName", "손님");
        order.verify(bannedWords).filterContent("content", "hello");
        order.verify(duplicates).check("ip:203.0.113.1", "hello");
    }

    @Test
    void missingCaptchaStopsBeforeCounting() {
        doThrow(new BusinessException(ErrorCode.CAPTCHA_FAILED, "x")).when(captcha).verify(null, null);
        assertCode(() -> guard.guardNew(WriteGuard.Kind.GUESTBOOK, WriteGuard.Writer.guest(null), "hello", null,
                "손님"), ErrorCode.CAPTCHA_FAILED);
        verifyNoInteractions(rateLimits, bannedWords, duplicates);
    }

    @Test
    void memberSkipsCaptchaAndGuestNameAndIsCountedById() {
        when(bannedWords.filterContent("content", "hello")).thenReturn("hello");
        guard.guardNew(WriteGuard.Kind.GUESTBOOK, WriteGuard.Writer.member(member, "203.0.113.1"), "hello", null,
                null);
        verifyNoInteractions(captcha);
        verify(rateLimits).check(RateLimitKind.GUESTBOOK, "u:7");
        verify(bannedWords, never()).requireCleanName(anyString(), any());
        verify(duplicates).check("u:7", "hello");
    }

    @Test
    void rateLimitAndDuplicateErrorsPropagate() {
        doThrow(BusinessException.retryAfter(ErrorCode.TOO_MANY_REQUESTS, "x", 30)).when(rateLimits)
                .check(RateLimitKind.COMMENT, "u:7");
        assertCode(() -> guard.guardNew(WriteGuard.Kind.COMMENT, WriteGuard.Writer.member(member, null), "a", null,
                null), ErrorCode.TOO_MANY_REQUESTS);
        verifyNoInteractions(bannedWords, duplicates);

        User other = TestEntities.user(8L);
        when(bannedWords.filterContent("content", "spam spam spam")).thenReturn("spam spam spam");
        doThrow(new BusinessException(ErrorCode.DUPLICATE_CONTENT_SPAM, "x")).when(duplicates)
                .check("u:8", "spam spam spam");
        assertCode(() -> guard.guardNew(WriteGuard.Kind.COMMENT, WriteGuard.Writer.member(other, null),
                "spam spam spam", null, null), ErrorCode.DUPLICATE_CONTENT_SPAM);
    }

    @Test
    void bannedWordRejectionHasFieldError() {
        when(bannedWords.filterContent("content", "bad")).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED,
                "x", List.of(FieldError.of("content", "BANNED_WORD"))));
        assertCode(() -> guard.guardNew(WriteGuard.Kind.COMMENT, WriteGuard.Writer.member(member, null), "bad", null,
                null), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(duplicates);
    }

    @Test
    void adminsSkipRateLimitAndDuplicateCheck() {
        TestEntities.with(member, "role", UserRole.ADMIN);
        when(bannedWords.filterContent("content", "hello")).thenReturn("hello");
        guard.guardNew(WriteGuard.Kind.COMMENT, WriteGuard.Writer.member(member, null), "hello", null, null);
        verifyNoInteractions(rateLimits, duplicates);
        assertThat(WriteGuard.isExempt(member)).isTrue();
        TestEntities.with(member, "role", UserRole.SUPER_ADMIN);
        assertThat(WriteGuard.isExempt(member)).isTrue();
        TestEntities.with(member, "role", UserRole.USER);
        assertThat(WriteGuard.isExempt(member)).isFalse();
    }

    @Test
    void editChecksBannedWordsOnly() {
        when(bannedWords.filterContent("content", "edited")).thenReturn("e*ited");
        assertThat(guard.guardEdit("edited")).isEqualTo("e*ited");
        verifyNoInteractions(captcha, rateLimits, duplicates);
    }
}
