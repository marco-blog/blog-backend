package net.java21.blog.backend.post.service;

import static net.java21.blog.backend.post.service.PostDraftServiceTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.http.Cookie;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.security.PasswordAttemptGuard;
import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.security.JwtProvider;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 보호 글 열기(T076, FR-062·FR-063): 목록 노출 가능한 보호 글만, 틀리면 400, 같은 방문자·IP 5회 연속 실패면 10분 동안 429,
 * 맞으면 본문 상세와 열람 쿠키. 성공하면 실패 횟수를 지운다.
 */
@ExtendWith(MockitoExtension.class)
class PostUnlockServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String HASH = ENCODER.encode("1234");

    @Mock
    private PostRepository postRepository;
    @Mock
    private PostService postService;

    private final AtomicLong nanos = new AtomicLong();
    private PostUnlockService service;
    private PostUnlockCookies cookies;
    private Post post;

    @BeforeEach
    void setUp() {
        JwtProvider jwt = new JwtProvider(new AuthProperties(Duration.ofMinutes(30), Duration.ofHours(4),
                Duration.ofDays(7), Duration.ofSeconds(10), "unit-test-only-jwt-secret-0123456789abcdef", 5,
                Duration.ofMinutes(10)), new MutableClock(NOW));
        cookies = new PostUnlockCookies(jwt, PostsProperties.defaults());
        PasswordAttemptGuard guard = new PasswordAttemptGuard(PostsProperties.defaults(), nanos::get);
        service = new PostUnlockService(postRepository, postService, ENCODER, guard, cookies);
        post = TestEntities.post(100L, TestEntities.blog(10L, TestEntities.user(1L), "marco"), "t");
    }

    @Test
    void correctPasswordReturnsUnlockedDetailAndCookie() {
        protect();
        PostDetailResponse detail = detail();
        when(postService.detailOf(post, null, false)).thenReturn(detail);

        PostUnlockService.Unlocked unlocked = service.unlock(100L, null, "1234", "v:abc", "203.0.113.1");

        assertThat(unlocked.detail()).isSameAs(detail);
        assertThat(unlocked.cookie().getName()).isEqualTo("post_unlock_100");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(unlocked.cookie().getName(), unlocked.cookie().getValue()));
        assertThat(cookies.isUnlocked(post, request)).isTrue();
    }

    @Test
    void wrongOrMissingPasswordIsMismatch() {
        protect();
        assertCode(() -> service.unlock(100L, null, "nope", "v:abc", "203.0.113.1"),
                ErrorCode.POST_PASSWORD_MISMATCH);
        assertCode(() -> service.unlock(100L, null, null, "v:abc", "203.0.113.1"),
                ErrorCode.POST_PASSWORD_MISMATCH);
        assertCode(() -> service.unlock(100L, null, "", "v:abc", "203.0.113.1"),
                ErrorCode.POST_PASSWORD_MISMATCH);
        verify(postService, never()).detailOf(any(), any(), anyBoolean());
    }

    @Test
    void fiveFailuresLockForTenMinutesEvenWithTheRightPassword() {
        protect();
        for (int i = 0; i < 5; i++) {
            assertCode(() -> service.unlock(100L, null, "nope", "v:abc", "203.0.113.1"),
                    ErrorCode.POST_PASSWORD_MISMATCH);
        }
        BusinessException locked = assertThrows(BusinessException.class,
                () -> service.unlock(100L, null, "1234", "v:abc", "203.0.113.1"));
        assertThat(locked.errorCode()).isEqualTo(ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED);
        assertThat(locked.retryAfterSeconds()).isPositive();
        assertCode(() -> service.unlock(100L, null, "1234", "v:other", "203.0.113.1"),
                ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED);

        nanos.addAndGet(Duration.ofMinutes(10).plusSeconds(1).toNanos());
        when(postService.detailOf(post, null, false)).thenReturn(detail());
        assertThat(service.unlock(100L, null, "1234", "v:abc", "203.0.113.1").detail()).isNotNull();
    }

    @Test
    void successResetsTheFailureCount() {
        protect();
        when(postService.detailOf(post, 7L, false)).thenReturn(detail());
        for (int i = 0; i < 4; i++) {
            assertCode(() -> service.unlock(100L, 7L, "nope", "u:7", "203.0.113.1"),
                    ErrorCode.POST_PASSWORD_MISMATCH);
        }
        service.unlock(100L, 7L, "1234", "u:7", "203.0.113.1");
        for (int i = 0; i < 4; i++) {
            assertCode(() -> service.unlock(100L, 7L, "nope", "u:7", "203.0.113.1"),
                    ErrorCode.POST_PASSWORD_MISMATCH);
        }
        assertThat(service.unlock(100L, 7L, "1234", "u:7", "203.0.113.1")).isNotNull();
    }

    @Test
    void onlyListableProtectedPostsCanBeUnlocked() {
        when(postRepository.findWithBlogAndOwner(404L)).thenReturn(Optional.empty());
        assertCode(() -> service.unlock(404L, null, "1234", null, "203.0.113.1"), ErrorCode.POST_NOT_FOUND);

        post.publish("t", "b", "<p>b</p>", "b", "b", null, PostVisibility.PUBLIC, true, NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        assertCode(() -> service.unlock(100L, null, "1234", null, "203.0.113.1"), ErrorCode.POST_NOT_FOUND);

        Post draft = TestEntities.post(101L, post.getBlog(), "d");
        when(postRepository.findWithBlogAndOwner(101L)).thenReturn(Optional.of(draft));
        assertCode(() -> service.unlock(101L, null, "1234", null, "203.0.113.1"), ErrorCode.POST_NOT_FOUND);
        verify(postService, never()).detailOf(any(), any(), anyBoolean());
        verify(postRepository, never()).incrementViewCount(anyLong());
    }

    private void protect() {
        post.publish("t", "b", "<p>b</p>", "b", "b", null, PostVisibility.PROTECTED, true, NOW);
        post.applyProtection(HASH);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
    }

    private static PostDetailResponse detail() {
        return new PostDetailResponse(100L, "marco", "t", "<p>b</p>", null, "b", null, null, List.of(),
                PostVisibility.PROTECTED, PostStatus.PUBLISHED, 0, 0, true, new PostDetailResponse.Author("m", null),
                null, null, NOW, NOW, 0, null, null, false, false, null);
    }
}
