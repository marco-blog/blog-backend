package net.java21.blog.backend.like.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.like.dto.LikeStateResponse;
import net.java21.blog.backend.like.repository.PostLikeRepository;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 좋아요 규칙(T016, FR-030, AS1, research D1): 발행되었고 상세를 볼 수 있는 글만(아니면 404 {@code POST_NOT_FOUND}), 자기 글 허용,
 * 이미 누른 글은 수를 바꾸지 않고 같은 응답, 취소는 글 상태와 무관, 없는 글 404, 응답 {@code { postId, liked, likeCount }}.
 */
@ExtendWith(MockitoExtension.class)
class PostLikeServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final long OWNER = 1L;
    private static final long READER = 2L;

    @Mock
    private PostRepository postRepository;
    @Mock
    private PostLikeRepository likeRepository;

    private PostLikeService service;
    private User owner;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        service = new PostLikeService(postRepository, likeRepository, new MutableClock(NOW));
        owner = TestEntities.user(OWNER);
        blog = TestEntities.blog(10L, owner, "marco");
        post = TestEntities.post(100L, blog, "제목");
    }

    @Test
    void likeInsertsRowAndIncrementsCount() {
        publish(PostVisibility.PUBLIC);
        when(likeRepository.lockLikeCount(100L)).thenReturn(Optional.of(3));
        when(likeRepository.insertIgnore(READER, 100L, NOW)).thenReturn(1);

        assertThat(service.like(READER, 100L)).isEqualTo(new LikeStateResponse(100L, true, 4));
        verify(likeRepository).changeLikeCount(100L, 1);
    }

    @Test
    void likingAgainKeepsCountAndAnswersTheSame() {
        publish(PostVisibility.PUBLIC);
        when(likeRepository.lockLikeCount(100L)).thenReturn(Optional.of(4));
        when(likeRepository.insertIgnore(READER, 100L, NOW)).thenReturn(0);

        assertThat(service.like(READER, 100L)).isEqualTo(new LikeStateResponse(100L, true, 4));
        verify(likeRepository, never()).changeLikeCount(anyLong(), anyInt());
    }

    @Test
    void ownerMayLikeOwnPostEvenPrivate() {
        publish(PostVisibility.PRIVATE);
        when(likeRepository.lockLikeCount(100L)).thenReturn(Optional.of(0));
        when(likeRepository.insertIgnore(OWNER, 100L, NOW)).thenReturn(1);

        assertThat(service.like(OWNER, 100L).likeCount()).isEqualTo(1);
    }

    @Test
    void othersPrivatePostIsNotFound() {
        publish(PostVisibility.PRIVATE);
        expectNotFound(() -> service.like(READER, 100L));
    }

    @Test
    void draftIsNotFoundEvenForOwner() {
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        expectNotFound(() -> service.like(OWNER, 100L));
        expectNotFound(() -> service.like(READER, 100L));
    }

    @Test
    void trashedPostIsNotFound() {
        publish(PostVisibility.PUBLIC);
        post.moveToTrash(NOW);
        expectNotFound(() -> service.like(READER, 100L));
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void postOfSuspendedOrWithdrawnAuthorIsNotFound(UserStatus status) {
        publish(PostVisibility.PUBLIC);
        TestEntities.with(owner, "status", status);
        expectNotFound(() -> service.like(READER, 100L));
    }

    @Test
    void postOfDeletedBlogIsNotFound() {
        publish(PostVisibility.PUBLIC);
        blog.delete(NOW);
        expectNotFound(() -> service.like(READER, 100L));
    }

    @Test
    void missingPostIsNotFound() {
        when(postRepository.findWithBlogAndOwner(404L)).thenReturn(Optional.empty());
        expectNotFound(() -> service.like(READER, 404L));
        verify(likeRepository, never()).insertIgnore(anyLong(), anyLong(), any());
    }

    @Test
    void unlikeRemovesRowAndDecrementsRegardlessOfPostState() {
        when(likeRepository.lockLikeCount(100L)).thenReturn(Optional.of(4));
        when(likeRepository.delete(READER, 100L)).thenReturn(1);

        assertThat(service.unlike(READER, 100L)).isEqualTo(new LikeStateResponse(100L, false, 3));
        verify(likeRepository).changeLikeCount(100L, -1);
        verifyNoInteractions(postRepository);
    }

    @Test
    void unlikeWithoutLikeIsStillOk() {
        when(likeRepository.lockLikeCount(100L)).thenReturn(Optional.of(4));
        when(likeRepository.delete(READER, 100L)).thenReturn(0);

        assertThat(service.unlike(READER, 100L)).isEqualTo(new LikeStateResponse(100L, false, 4));
        verify(likeRepository, never()).changeLikeCount(anyLong(), anyInt());
    }

    @Test
    void unlikeNeverReportsNegativeCount() {
        when(likeRepository.lockLikeCount(100L)).thenReturn(Optional.of(0));
        when(likeRepository.delete(READER, 100L)).thenReturn(1);

        assertThat(service.unlike(READER, 100L).likeCount()).isZero();
    }

    @Test
    void unlikeOfMissingPostIsNotFound() {
        when(likeRepository.lockLikeCount(404L)).thenReturn(Optional.empty());
        expectNotFound(() -> service.unlike(READER, 404L));
        verify(likeRepository, never()).delete(anyLong(), anyLong());
    }

    @Test
    void isLikedByIsNullForAnonymous() {
        assertThat(service.isLikedBy(null, 100L)).isNull();
        when(likeRepository.existsByUserIdAndPostId(READER, 100L)).thenReturn(true);
        assertThat(service.isLikedBy(READER, 100L)).isTrue();
    }

    private void publish(PostVisibility visibility) {
        post.publish("제목", "본문", "<p>본문</p>", "본문", "본문", null, visibility, true, NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
    }

    private static void expectNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.POST_NOT_FOUND));
    }
}
