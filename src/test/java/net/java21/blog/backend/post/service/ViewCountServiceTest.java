package net.java21.blog.backend.post.service;

import static net.java21.blog.backend.post.service.PostDraftServiceTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.support.TestEntities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 조회수 중복 판단(T064, FR-020): 키 postId + 방문자 키, 30분 안 재조회는 세지 않음. */
@ExtendWith(MockitoExtension.class)
class ViewCountServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Mock
    private PostRepository postRepository;

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private ViewCountService service;
    private Post post;

    @BeforeEach
    void setUp() {
        service = new ViewCountService(postRepository, new PostsProperties(Duration.ofMinutes(30), 1000, "visitor_id", Duration.ofDays(365)),
                ticker);
        post = TestEntities.post(100L, TestEntities.blog(10L, TestEntities.user(1L), "marco"), "t");
    }

    @Test
    void sameVisitorWithin30MinutesCountsOnce() {
        publishPublic();

        assertThat(service.record(100L, null, "v:abc")).isTrue();
        nanos.addAndGet(Duration.ofMinutes(29).toNanos());
        assertThat(service.record(100L, null, "v:abc")).isFalse();
        verify(postRepository, times(1)).incrementViewCount(100L);

        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        assertThat(service.record(100L, null, "v:abc")).isTrue();
        verify(postRepository, times(2)).incrementViewCount(100L);
    }

    @Test
    void differentVisitorsOrMembersCountSeparately() {
        publishPublic();

        assertThat(service.record(100L, null, "v:abc")).isTrue();
        assertThat(service.record(100L, null, "v:def")).isTrue();
        assertThat(service.record(100L, 7L, "u:7")).isTrue();
        assertThat(service.record(100L, 7L, "u:7")).isFalse();
        verify(postRepository, times(3)).incrementViewCount(100L);
    }

    @Test
    void invisiblePostIsNotFoundAndNotCounted() {
        post.publish("t", "b", "<p>b</p>", "b", "b", null, PostVisibility.PRIVATE, true, NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(postRepository.findWithBlogAndOwner(404L)).thenReturn(Optional.empty());

        assertCode(() -> service.record(100L, null, "v:abc"), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.record(100L, 2L, "u:2"), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.record(404L, null, "v:abc"), ErrorCode.POST_NOT_FOUND);
        verify(postRepository, never()).incrementViewCount(anyLong());
    }

    @Test
    void ownerCanViewOwnPrivatePost() {
        post.publish("t", "b", "<p>b</p>", "b", "b", null, PostVisibility.PRIVATE, true, NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));

        assertThat(service.record(100L, 1L, "u:1")).isTrue();
    }

    @Test
    void productionConstructorUsesSystemTicker() {
        publishPublic();
        ViewCountService real = new ViewCountService(postRepository, new PostsProperties(Duration.ofMinutes(30), 10, "v", Duration.ofDays(1)));
        assertThat(real.record(100L, null, "v:x")).isTrue();
        assertThat(real.record(100L, null, "v:x")).isFalse();
    }

    private void publishPublic() {
        post.publish("t", "b", "<p>b</p>", "b", "b", null, PostVisibility.PUBLIC, true, NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
    }
}
