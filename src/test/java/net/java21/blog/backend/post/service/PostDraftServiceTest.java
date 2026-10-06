package net.java21.blog.backend.post.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostDraft;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.DraftResponse;
import net.java21.blog.backend.post.dto.DraftWriteRequest;
import net.java21.blog.backend.post.dto.LatestDraftResponse;
import net.java21.blog.backend.post.dto.SavedDraftResponse;
import net.java21.blog.backend.post.repository.PostDraftRepository;
import net.java21.blog.backend.post.repository.PostQueryRepository;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 작성·임시저장(T060, FR-013, FR-016, FR-108, AS8·9). */
@ExtendWith(MockitoExtension.class)
class PostDraftServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Mock
    private BlogRepository blogRepository;
    @Mock
    private PostRepository postRepository;
    @Mock
    private PostDraftRepository postDraftRepository;
    @Mock
    private PostQueryRepository postQueryRepository;

    private PostDraftService service;
    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        service = new PostDraftService(new BlogAccess(blogRepository), new PostAccess(postRepository), postRepository,
                postDraftRepository, postQueryRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        owner = TestEntities.user(1L);
        blog = TestEntities.blog(10L, owner, "marco");
    }

    // ---- 새 임시저장 ----

    @Test
    void createMakesDraftPostAndDraftCopy() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> {
            Post p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", 100L);
            return p;
        });

        SavedDraftResponse saved = service.create(1L, "marco",
                new DraftWriteRequest("제목", "본문", 3L, List.of("spring")));

        assertThat(saved).isEqualTo(new SavedDraftResponse(100L, NOW));
        ArgumentCaptor<Post> post = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(post.capture());
        assertThat(post.getValue().getStatus()).isEqualTo(PostStatus.DRAFT);
        assertThat(post.getValue().getTitle()).isEqualTo("제목");
        assertThat(post.getValue().getContentHtml()).isNull();
        ArgumentCaptor<PostDraft> draft = ArgumentCaptor.forClass(PostDraft.class);
        verify(postDraftRepository).save(draft.capture());
        assertThat(draft.getValue().getPost()).isSameAs(post.getValue());
        assertThat(draft.getValue().getContentMarkdown()).isEqualTo("본문");
        assertThat(draft.getValue().getCategoryId()).isEqualTo(3L);
        assertThat(draft.getValue().getTags()).containsExactly("spring");
        assertThat(draft.getValue().getSavedAt()).isEqualTo(NOW);
    }

    @Test
    void createAllowsEmptyTitle() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(postRepository.save(any(Post.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(1L, "marco", new DraftWriteRequest(null, null, null, null));

        ArgumentCaptor<Post> post = ArgumentCaptor.forClass(Post.class);
        verify(postRepository).save(post.capture());
        assertThat(post.getValue().getTitle()).isEmpty();
    }

    @Test
    void createInSomeoneElsesBlogIsForbidden() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));

        assertCode(() -> service.create(2L, "marco", new DraftWriteRequest("t", "b", null, null)), ErrorCode.FORBIDDEN);
        verify(postRepository, never()).save(any());
    }

    @Test
    void createInDeletedBlogIsNotFound() {
        blog.delete(NOW);
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));

        assertCode(() -> service.create(1L, "marco", new DraftWriteRequest("t", "b", null, null)),
                ErrorCode.BLOG_NOT_FOUND);
    }

    // ---- 저장 ----

    @Test
    void saveOfPublishedPostChangesOnlyTheDraftCopy() {
        Post post = published(100L);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(postDraftRepository.findById(100L)).thenReturn(Optional.empty());

        SavedDraftResponse saved = service.save(1L, 100L, new DraftWriteRequest("고친 제목", "고친 본문", null, List.of()));

        assertThat(saved).isEqualTo(new SavedDraftResponse(100L, NOW));
        assertThat(post.getTitle()).isEqualTo("발행 제목");
        assertThat(post.getContentHtml()).isEqualTo("<p>발행 본문</p>");
        ArgumentCaptor<PostDraft> draft = ArgumentCaptor.forClass(PostDraft.class);
        verify(postDraftRepository).save(draft.capture());
        assertThat(draft.getValue().getTitle()).isEqualTo("고친 제목");
        assertThat(draft.getValue().getContentMarkdown()).isEqualTo("고친 본문");
    }

    @Test
    void saveOfUnpublishedPostUpdatesExistingCopyAndSyncsTitle() {
        Post post = TestEntities.post(100L, blog, "옛 제목");
        PostDraft existing = new PostDraft(post);
        existing.write("옛 제목", "옛 본문", null, null, NOW.minusSeconds(60));
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(postDraftRepository.findById(100L)).thenReturn(Optional.of(existing));

        service.save(1L, 100L, new DraftWriteRequest("새 제목", "새 본문", null, null));

        assertThat(existing.getTitle()).isEqualTo("새 제목");
        assertThat(existing.getSavedAt()).isEqualTo(NOW);
        assertThat(post.getTitle()).isEqualTo("새 제목");
        verify(postDraftRepository).save(existing);
    }

    @Test
    void saveOfOthersPostIsForbidden() {
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(published(100L)));

        assertCode(() -> service.save(2L, 100L, new DraftWriteRequest("t", "b", null, null)), ErrorCode.FORBIDDEN);
    }

    @Test
    void saveOfTrashedOrMissingPostIsNotFound() {
        Post trashed = published(100L);
        trashed.moveToTrash(NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(trashed));
        when(postRepository.findWithBlogAndOwner(101L)).thenReturn(Optional.empty());

        assertCode(() -> service.save(1L, 100L, new DraftWriteRequest("t", "b", null, null)), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.save(1L, 101L, new DraftWriteRequest("t", "b", null, null)), ErrorCode.POST_NOT_FOUND);
    }

    @Test
    void postsOfDeletedBlogAreNotFoundEvenForOwner() {
        Post post = published(100L);
        blog.delete(NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));

        assertCode(() -> service.get(1L, 100L), ErrorCode.POST_NOT_FOUND);
    }

    // ---- 불러오기 ----

    @Test
    void getReturnsDraftCopyWhenPresent() {
        Post post = published(100L);
        PostDraft draft = new PostDraft(post);
        draft.write("사본 제목", "사본 본문", 3L, List.of("jpa"), NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(postDraftRepository.findById(100L)).thenReturn(Optional.of(draft));

        assertThat(service.get(1L, 100L)).isEqualTo(new DraftResponse("사본 제목", "사본 본문", 3L, List.of("jpa"), NOW));
    }

    @Test
    void getFallsBackToPublishedContent() {
        Post post = published(100L);
        TestEntities.with(post, "updatedAt", NOW);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(postDraftRepository.findById(100L)).thenReturn(Optional.empty());

        assertThat(service.get(1L, 100L)).isEqualTo(new DraftResponse("발행 제목", "발행 본문", null, List.of(), NOW));
    }

    // ---- 사본 폐기 ----

    @Test
    void discardDeletesOnlyTheCopyOfPublishedPost() {
        Post post = published(100L);
        PostDraft draft = new PostDraft(post);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(postDraftRepository.findById(100L)).thenReturn(Optional.of(draft));

        service.discard(1L, 100L);

        verify(postDraftRepository).delete(draft);
        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
    }

    @Test
    void discardOfUnpublishedPostIsConflict() {
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(TestEntities.post(100L, blog, "t")));

        assertCode(() -> service.discard(1L, 100L), ErrorCode.POST_NOT_PUBLISHED);
        verify(postDraftRepository, never()).delete(any());
    }

    // ---- 최근 임시저장 ----

    @Test
    void latestReturnsNewestDraftOrNull() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(postQueryRepository.findLatestDraft(10L))
                .thenReturn(Optional.of(new LatestDraftResponse(100L, "t", NOW)))
                .thenReturn(Optional.empty());

        assertThat(service.latest(1L, "marco")).isEqualTo(new LatestDraftResponse(100L, "t", NOW));
        assertThat(service.latest(1L, "marco")).isNull();
    }

    @Test
    void latestOfOthersBlogIsForbidden() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));

        assertCode(() -> service.latest(2L, "marco"), ErrorCode.FORBIDDEN);
    }

    @Test
    void blogOfSuspendedOwnerIsNotFound() {
        TestEntities.with(owner, "status", net.java21.blog.backend.user.domain.UserStatus.SUSPENDED);
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));

        assertCode(() -> service.latest(1L, "marco"), ErrorCode.BLOG_NOT_FOUND);
        assertThat(blog.getStatus()).isEqualTo(BlogStatus.ACTIVE);
    }

    private Post published(long id) {
        Post post = TestEntities.post(id, blog, "");
        post.publish("발행 제목", "발행 본문", "<p>발행 본문</p>", "발행 본문", "발행 본문", null, PostVisibility.PUBLIC,
                true, NOW.minusSeconds(3600));
        return post;
    }

    static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode()).isEqualTo(code);
    }
}
