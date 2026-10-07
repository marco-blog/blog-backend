package net.java21.blog.backend.post.service;

import static net.java21.blog.backend.post.service.PostDraftServiceTest.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.stats.BlogCalendar;
import net.java21.blog.backend.stats.StatsProperties;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.repository.CategoryRepository;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.like.repository.PostLikeRepository;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostDetailResponse;
import net.java21.blog.backend.post.dto.PostLink;
import net.java21.blog.backend.post.dto.PostListFilter;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.post.repository.PostDraftRepository;
import net.java21.blog.backend.post.repository.PostQueryRepository;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.post.repository.PostSummaryRow;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** 글 상세(노출 매트릭스)·목록·휴지통(T062, FR-017~019, FR-084, AS11~13·15). */
@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final long OWNER = 1L;
    private static final long STRANGER = 2L;

    @Mock
    private PostRepository postRepository;
    @Mock
    private PostDraftRepository postDraftRepository;
    @Mock
    private PostQueryRepository postQueryRepository;
    @Mock
    private BlogRepository blogRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private TagQueryRepository tagQueryRepository;
    @Mock
    private PostLikeRepository postLikeRepository;

    private PostService service;
    private User owner;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        service = new PostService(postRepository, postDraftRepository, postQueryRepository,
                new PostAccess(postRepository), new BlogAccess(blogRepository), new CategoryAccess(categoryRepository),
                tagQueryRepository, postLikeRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                new BlogCalendar(StatsProperties.defaults(), Clock.fixed(NOW, ZoneOffset.UTC)));
        owner = TestEntities.user(OWNER);
        blog = TestEntities.blog(10L, owner, "marco");
        post = TestEntities.post(100L, blog, "제목");
    }

    // ---- 상세: 노출 매트릭스 ----

    @Test
    void publicPublishedPostIsVisibleToEveryoneWithoutMarkdown() {
        publish(PostVisibility.PUBLIC);
        stubFound();
        when(postQueryRepository.findPrevious(10L, 100L, NOW)).thenReturn(Optional.of(new PostLink(99L, "이전")));
        when(postQueryRepository.findNext(10L, 100L, NOW)).thenReturn(Optional.empty());

        PostDetailResponse anonymous = service.detail(100L, null);

        assertThat(anonymous.contentHtml()).isEqualTo("<p>본문</p>");
        assertThat(anonymous.contentMarkdown()).isNull();
        assertThat(anonymous.prev()).isEqualTo(new PostLink(99L, "이전"));
        assertThat(anonymous.next()).isNull();
        assertThat(anonymous.author().nickname()).isEqualTo(owner.getNickname());
        assertThat(anonymous.tags()).isEmpty();
        assertThat(anonymous.category()).isNull();
        assertThat(anonymous.topicId()).isNull();

        assertThat(service.detail(100L, STRANGER).contentMarkdown()).isNull();
        assertThat(service.detail(100L, OWNER).contentMarkdown()).isEqualTo("본문");
    }

    /** 003 T070: 글의 주제(소분류 id). */
    @Test
    void detailCarriesTopicId() {
        publish(PostVisibility.PUBLIC);
        post.assignTopic(net.java21.blog.backend.support.TestEntities.topic(31L,
                net.java21.blog.backend.support.TestEntities.topic(3L, null, "knowledge"), "it-internet"));
        stubFound();
        when(postQueryRepository.findPrevious(10L, 100L, NOW)).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(10L, 100L, NOW)).thenReturn(Optional.empty());

        assertThat(service.detail(100L, null).topicId()).isEqualTo(31L);
    }

    /**
     * T196: 상세의 {@code commentEnabled}는 방문자에게 블로그 설정을 반영한 값(FR-029)이다. 주인에게는 글별 설정 그대로를 주어
     * 작성 화면이 블로그 설정 때문에 글별 설정을 바꿔 저장하지 않게 한다. {@code commentCount}는 표시되는 댓글 수 그대로.
     */
    @Test
    void commentEnabledReflectsBlogSettingForVisitors() {
        publish(PostVisibility.PUBLIC);
        stubFound();
        when(postQueryRepository.findPrevious(10L, 100L, NOW)).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(10L, 100L, NOW)).thenReturn(Optional.empty());
        net.java21.blog.backend.support.TestEntities.with(post, "commentCount", 3);

        assertThat(service.detail(100L, null).commentEnabled()).isTrue();
        assertThat(service.detail(100L, null).commentCount()).isEqualTo(3);

        blog.changeCommentEnabled(false);
        assertThat(service.detail(100L, null).commentEnabled()).isFalse();
        assertThat(service.detail(100L, STRANGER).commentEnabled()).isFalse();
        assertThat(service.detail(100L, OWNER).commentEnabled()).isTrue();
    }

    /** 002 T023: 좋아요 수와 내가 눌렀는지(비로그인 null, 누름 true, 안 누름 false). 좋아요 여부는 로그인했을 때만 1회 조회. */
    @Test
    void detailCarriesLikeCountAndLikedByMe() {
        publish(PostVisibility.PUBLIC);
        stubFound();
        when(postQueryRepository.findPrevious(10L, 100L, NOW)).thenReturn(Optional.empty());
        when(postQueryRepository.findNext(10L, 100L, NOW)).thenReturn(Optional.empty());
        TestEntities.with(post, "likeCount", 4);
        when(postLikeRepository.existsByUserIdAndPostId(STRANGER, 100L)).thenReturn(true);
        when(postLikeRepository.existsByUserIdAndPostId(OWNER, 100L)).thenReturn(false);

        PostDetailResponse anonymous = service.detail(100L, null);
        assertThat(anonymous.likeCount()).isEqualTo(4);
        assertThat(anonymous.likedByMe()).isNull();
        assertThat(service.detail(100L, STRANGER).likedByMe()).isTrue();
        assertThat(service.detail(100L, OWNER).likedByMe()).isFalse();
        verify(postLikeRepository, never()).existsByUserIdAndPostId(org.mockito.ArgumentMatchers.isNull(), anyLong());
    }

    @Test
    void privatePostIsNotFoundForOthersButVisibleToOwner() {
        publish(PostVisibility.PRIVATE);
        stubFound();

        assertCode(() -> service.detail(100L, null), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.detail(100L, STRANGER), ErrorCode.POST_NOT_FOUND);
        assertThat(service.detail(100L, OWNER).visibility()).isEqualTo(PostVisibility.PRIVATE);
    }

    @Test
    void draftIsNotFoundForOthersButVisibleToOwnerWithoutPrevNext() {
        stubFound();

        assertCode(() -> service.detail(100L, null), ErrorCode.POST_NOT_FOUND);
        PostDetailResponse mine = service.detail(100L, OWNER);
        assertThat(mine.status()).isEqualTo(PostStatus.DRAFT);
        assertThat(mine.prev()).isNull();
        verify(postQueryRepository, never()).findPrevious(anyLong(), anyLong(), any());
    }

    @Test
    void deletedPostIsNotFoundEvenForOwner() {
        publish(PostVisibility.PUBLIC);
        post.moveToTrash(NOW);
        stubFound();

        assertCode(() -> service.detail(100L, null), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.detail(100L, OWNER), ErrorCode.POST_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void postsOfSuspendedOrWithdrawnAuthorsAreNotFound(UserStatus status) {
        publish(PostVisibility.PUBLIC);
        TestEntities.with(owner, "status", status);
        stubFound();

        assertCode(() -> service.detail(100L, null), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.detail(100L, OWNER), ErrorCode.POST_NOT_FOUND);
    }

    @Test
    void postsOfDeletedBlogAreNotFound() {
        publish(PostVisibility.PUBLIC);
        blog.delete(NOW);
        stubFound();

        assertCode(() -> service.detail(100L, null), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.detail(100L, OWNER), ErrorCode.POST_NOT_FOUND);
    }

    @Test
    void missingPostIsNotFound() {
        when(postRepository.findWithBlogAndOwner(404L)).thenReturn(Optional.empty());
        assertCode(() -> service.detail(404L, null), ErrorCode.POST_NOT_FOUND);
    }

    // ---- 목록 ----

    @Test
    void blogPostsUsesVisibleBlogAndMapsRows() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        PostSummaryRow row = new PostSummaryRow(100L, "제목", "요약", "/media/k3Jd9fQ2xLmA7pZ0bR5tYw", null, null, 3,
                1, PostVisibility.PUBLIC, PostStatus.PUBLISHED, NOW, NOW, false);
        when(postQueryRepository.findListablePosts(10L, PostListFilter.NONE, PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        var page = service.blogPosts("marco", PostListFilter.NONE, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        PostSummaryResponse item = page.getContent().getFirst();
        assertThat(item.id()).isEqualTo(100L);
        assertThat(item.hasDraft()).isFalse();
        assertThat(item.tags()).isEmpty();
        assertThat(item.deletedAt()).isNull();
    }

    @Test
    void blogPostsOfDeletedBlogIsNotFound() {
        blog.delete(NOW);
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        assertCode(() -> service.blogPosts("marco", PostListFilter.NONE, PageRequest.of(0, 20)), ErrorCode.BLOG_NOT_FOUND);
    }

    // ---- 휴지통 ----

    @Test
    void deleteMovesToTrashKeepingPreviousStatus() {
        publish(PostVisibility.PRIVATE);
        stubFound();

        service.delete(OWNER, 100L);

        assertThat(post.getStatus()).isEqualTo(PostStatus.DELETED);
        assertThat(post.getStatusBeforeDelete()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(post.getDeletedAt()).isEqualTo(NOW);
        assertThat(post.getVisibility()).isEqualTo(PostVisibility.PRIVATE);
    }

    @Test
    void othersCannotDeleteOrRestore() {
        publish(PostVisibility.PUBLIC);
        stubFound();

        assertCode(() -> service.delete(STRANGER, 100L), ErrorCode.FORBIDDEN);
        assertCode(() -> service.restore(STRANGER, 100L), ErrorCode.FORBIDDEN);
        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
    }

    @Test
    void deletingTrashedPostIsNotFound() {
        post.moveToTrash(NOW);
        stubFound();
        assertCode(() -> service.delete(OWNER, 100L), ErrorCode.POST_NOT_FOUND);
    }

    @Test
    void restoreReturnsPostToStatusBeforeDelete() {
        publish(PostVisibility.PUBLIC);
        post.moveToTrash(NOW);
        stubFound();
        when(postDraftRepository.existsById(100L)).thenReturn(true);

        PostSummaryResponse restored = service.restore(OWNER, 100L);

        assertThat(post.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(post.getDeletedAt()).isNull();
        assertThat(post.getStatusBeforeDelete()).isNull();
        assertThat(restored.status()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(restored.hasDraft()).isTrue();
        assertThat(restored.deletedAt()).isNull();
    }

    @Test
    void restoreOfDraftGoesBackToDraft() {
        post.moveToTrash(NOW);
        stubFound();

        assertThat(service.restore(OWNER, 100L).status()).isEqualTo(PostStatus.DRAFT);
    }

    @Test
    void restoreOfPostNotInTrashIsUnprocessable() {
        publish(PostVisibility.PUBLIC);
        stubFound();
        assertCode(() -> service.restore(OWNER, 100L), ErrorCode.POST_NOT_IN_TRASH);
    }

    @Test
    void restoreInDeletedBlogIsNotFound() {
        post.moveToTrash(NOW);
        blog.delete(NOW);
        stubFound();
        assertCode(() -> service.restore(OWNER, 100L), ErrorCode.POST_NOT_FOUND);
    }

    // ---- 004 예약 취소·예약 글 복구·보호 글 상세 (T079) ----

    @Test
    void unscheduleTurnsScheduledPostIntoDraft() {
        schedule(NOW.plusSeconds(3600));
        stubFound();

        PostSummaryResponse summary = service.unschedule(OWNER, 100L);

        assertThat(post.getStatus()).isEqualTo(PostStatus.DRAFT);
        assertThat(post.getScheduledAt()).isNull();
        assertThat(summary.status()).isEqualTo(PostStatus.DRAFT);
        assertThat(summary.scheduledAt()).isNull();
    }

    @Test
    void unscheduleRulesForNotScheduledAndStrangers() {
        publish(PostVisibility.PUBLIC);
        stubFound();
        assertCode(() -> service.unschedule(OWNER, 100L), ErrorCode.POST_NOT_SCHEDULED);

        Post scheduled = TestEntities.post(101L, blog, "예약");
        scheduled.schedule("예약", "b", "<p>b</p>", "b", "b", null, PostVisibility.PUBLIC, true, NOW.plusSeconds(60));
        when(postRepository.findWithBlogAndOwner(101L)).thenReturn(Optional.of(scheduled));
        assertCode(() -> service.unschedule(STRANGER, 101L), ErrorCode.FORBIDDEN);
    }

    @Test
    void restoredScheduledPostIsScheduledAgainWithItsTime() {
        Instant at = NOW.plusSeconds(3600);
        schedule(at);
        post.moveToTrash(NOW);
        stubFound();

        PostSummaryResponse restored = service.restore(OWNER, 100L);

        assertThat(restored.status()).isEqualTo(PostStatus.SCHEDULED);
        assertThat(restored.scheduledAt()).isEqualTo(at);
    }

    @Test
    void scheduledPostIsOnlyVisibleToItsOwnerWithScheduledAt() {
        Instant at = NOW.plusSeconds(3600);
        schedule(at);
        stubFound();

        assertCode(() -> service.detail(100L, null), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.detail(100L, STRANGER), ErrorCode.POST_NOT_FOUND);
        PostDetailResponse forOwner = service.detail(100L, OWNER);
        assertThat(forOwner.status()).isEqualTo(PostStatus.SCHEDULED);
        assertThat(forOwner.scheduledAt()).isEqualTo(at);
    }

    @Test
    void protectedPostIsLockedUntilUnlocked() {
        publish(PostVisibility.PROTECTED);
        post.applyProtection("$2a$04$hash");
        stubFound();

        PostDetailResponse locked = service.detail(100L, STRANGER, p -> false);
        assertThat(locked.locked()).isTrue();
        assertThat(locked.contentHtml()).isNull();
        assertThat(locked.summary()).isNull();
        assertThat(locked.tags()).isEmpty();
        assertThat(locked.title()).isEqualTo("제목");

        PostDetailResponse unlocked = service.detail(100L, STRANGER, p -> true);
        assertThat(unlocked.locked()).isFalse();
        assertThat(unlocked.contentHtml()).isEqualTo("<p>본문</p>");

        assertThat(service.detail(100L, OWNER, p -> false).locked()).as("주인").isFalse();
        assertThat(service.detail(100L, null).locked()).isTrue();
    }

    private void schedule(Instant at) {
        post.schedule("제목", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, true, at);
    }

    private void publish(PostVisibility visibility) {
        post.publish("제목", "본문", "<p>본문</p>", "본문", "본문", null, visibility, true, NOW);
    }

    private void stubFound() {
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
    }
}
