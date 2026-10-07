package net.java21.blog.backend.trackback.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.post.service.PostAccess;
import net.java21.blog.backend.post.service.PostUnlockCheck;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.trackback.domain.PingErrorCode;
import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import net.java21.blog.backend.trackback.domain.TrackbackStatus;
import net.java21.blog.backend.trackback.dto.ManagedTrackbackResponse;
import net.java21.blog.backend.trackback.dto.TrackbackPingResponse;
import net.java21.blog.backend.trackback.dto.TrackbackResponse;
import net.java21.blog.backend.trackback.repository.TrackbackQueryRepository;
import net.java21.blog.backend.trackback.repository.TrackbackRepository;
import net.java21.blog.backend.trackback.repository.TrackbackRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** 트랙백 목록·삭제·관리·보낸 기록(005 T088, FR-049·051·053, AS4). */
@ExtendWith(MockitoExtension.class)
class TrackbackServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final long OWNER = 1L;
    private static final long STRANGER = 2L;
    private static final PageRequest PAGE = PageRequest.of(0, 20);

    @Mock
    private PostRepository postRepository;
    @Mock
    private TrackbackRepository trackbackRepository;
    @Mock
    private TrackbackQueryRepository queryRepository;
    @Mock
    private BlogRepository blogRepository;

    private TrackbackService service;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        service = new TrackbackService(postRepository, trackbackRepository, queryRepository,
                new BlogAccess(blogRepository), new PostAccess(postRepository));
        blog = TestEntities.blog(10L, TestEntities.user(OWNER), "marco");
        post = TestEntities.post(100L, blog, "글");
    }

    private void publish(PostVisibility visibility) {
        post.publish("글", "본문", "<p>본문</p>", "본문", "요약", null, visibility, true, NOW);
    }

    private static TrackbackRow row(long id, TrackbackStatus status, Long sourcePostId) {
        return new TrackbackRow(id, "제목", "요약", "블로그", "https://ext.example/" + id, NOW, sourcePostId, status,
                100L, "글");
    }

    @Test
    void anyoneCanListTrackbacksOfAVisiblePost() {
        publish(PostVisibility.PUBLIC);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(queryRepository.findVisible(100L, PAGE))
                .thenReturn(new PageImpl<>(List.of(row(1L, TrackbackStatus.ACTIVE, 7L)), PAGE, 1));

        Page<TrackbackResponse> page = service.list(100L, null, PostUnlockCheck.NONE, PAGE);

        assertThat(page.getContent()).singleElement().satisfies(t -> {
            assertThat(t.url()).isEqualTo("https://ext.example/1");
            assertThat(t.internal()).isTrue();
            assertThat(t.receivedAt()).isEqualTo(NOW);
        });
    }

    @Test
    void listIs404WhenTheDetailIsNotVisible() {
        publish(PostVisibility.PRIVATE);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(postRepository.findWithBlogAndOwner(999L)).thenReturn(Optional.empty());

        assertCode(() -> service.list(100L, STRANGER, PostUnlockCheck.NONE, PAGE), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.list(999L, null, PostUnlockCheck.NONE, PAGE), ErrorCode.POST_NOT_FOUND);
        verifyNoInteractions(queryRepository);
    }

    @Test
    void ownerSeesTrackbacksOfAPrivatePostAndLockedProtectedPostsGiveAnEmptyPage() {
        publish(PostVisibility.PRIVATE);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(queryRepository.findVisible(100L, PAGE)).thenReturn(Page.empty(PAGE));
        assertThat(service.list(100L, OWNER, PostUnlockCheck.NONE, PAGE)).isEmpty();
        verify(queryRepository).findVisible(100L, PAGE);

        Post locked = TestEntities.post(101L, blog, "보호");
        locked.publish("보호", "본문", "<p>본문</p>", "본문", "요약", null, PostVisibility.PROTECTED, true, NOW);
        when(postRepository.findWithBlogAndOwner(101L)).thenReturn(Optional.of(locked));
        assertThat(service.list(101L, STRANGER, PostUnlockCheck.NONE, PAGE).getTotalElements()).isZero();
        verify(queryRepository, org.mockito.Mockito.never()).findVisible(eq(101L), any());
    }

    @Test
    void ownerDeletesActiveTrackback() {
        Trackback trackback = trackbackOn(post);
        when(trackbackRepository.findWithPostAndOwner(5L)).thenReturn(Optional.of(trackback));

        service.delete(OWNER, 5L);

        assertThat(trackback.getStatus()).isEqualTo(TrackbackStatus.DELETED);
    }

    @Test
    void deletingSomeoneElsesTrackbackIs403AndMissingDeletedOrHiddenIs404() {
        Trackback active = trackbackOn(post);
        when(trackbackRepository.findWithPostAndOwner(5L)).thenReturn(Optional.of(active));
        assertCode(() -> service.delete(STRANGER, 5L), ErrorCode.FORBIDDEN);
        assertThat(active.isActive()).isTrue();

        Trackback deleted = trackbackOn(post);
        deleted.markDeleted();
        Trackback hidden = trackbackOn(post);
        hidden.hide();
        when(trackbackRepository.findWithPostAndOwner(6L)).thenReturn(Optional.of(deleted));
        when(trackbackRepository.findWithPostAndOwner(7L)).thenReturn(Optional.of(hidden));
        when(trackbackRepository.findWithPostAndOwner(8L)).thenReturn(Optional.empty());
        assertCode(() -> service.delete(OWNER, 6L), ErrorCode.TRACKBACK_NOT_FOUND);
        assertCode(() -> service.delete(OWNER, 7L), ErrorCode.TRACKBACK_NOT_FOUND);
        assertCode(() -> service.delete(OWNER, 8L), ErrorCode.TRACKBACK_NOT_FOUND);
        assertThat(hidden.isHidden()).isTrue();
    }

    @Test
    void managedListIsForTheBlogOwner() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(queryRepository.findManaged(10L, PAGE)).thenReturn(new PageImpl<>(
                List.of(row(1L, TrackbackStatus.HIDDEN, null), row(2L, TrackbackStatus.ACTIVE, null)), PAGE, 2));

        Page<ManagedTrackbackResponse> page = service.managed(OWNER, "marco", PAGE);

        assertThat(page.getContent()).extracting(ManagedTrackbackResponse::hidden).containsExactly(true, false);
        assertThat(page.getContent().get(0).post()).isEqualTo(new ManagedTrackbackResponse.PostRef(100L, "글"));
        assertThat(page.getContent().get(0).internal()).isFalse();
        assertCode(() -> service.managed(STRANGER, "marco", PAGE), ErrorCode.FORBIDDEN);
    }

    @Test
    void pingsAreForThePostOwner() {
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        TrackbackPingLog failed = new TrackbackPingLog(post, "https://ext.example/tb");
        failed.fail(PingErrorCode.TIMEOUT, "Timed out", NOW);
        when(queryRepository.findRecentPings(100L, 50))
                .thenReturn(List.of(failed, new TrackbackPingLog(post, "https://ext.example/pending")));

        List<TrackbackPingResponse> pings = service.pings(OWNER, 100L);

        assertThat(pings).extracting(TrackbackPingResponse::status).containsExactly(PingStatus.FAILED,
                PingStatus.PENDING);
        assertThat(pings.get(0).errorCode()).isEqualTo(PingErrorCode.TIMEOUT);
        assertThat(pings.get(0).attemptedAt()).isEqualTo(NOW);
        assertCode(() -> service.pings(STRANGER, 100L), ErrorCode.FORBIDDEN);
    }

    private Trackback trackbackOn(Post target) {
        return new Trackback(target, null, "https://ext.example/x", "h".repeat(64), "t", null, null, null);
    }
}
