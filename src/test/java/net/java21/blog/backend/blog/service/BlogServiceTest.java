package net.java21.blog.backend.blog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.blog.BlogsProperties;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.domain.BlogStatus;
import net.java21.blog.backend.blog.domain.FeedContentMode;
import net.java21.blog.backend.blog.dto.BlogResponse;
import net.java21.blog.backend.blog.dto.CreateBlogRequest;
import net.java21.blog.backend.blog.dto.HandleAvailabilityResponse;
import net.java21.blog.backend.blog.dto.MyBlogsResponse;
import net.java21.blog.backend.blog.dto.UpdateBlogRequest;
import net.java21.blog.backend.blog.repository.BlogQueryRepository;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.blog.repository.MyBlogRow;
import net.java21.blog.backend.category.dto.CategoryNode;
import net.java21.blog.backend.category.repository.CategoryQueryRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.media.service.MediaReferenceService;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import net.java21.blog.backend.spam.BannedWordMatcher;
import net.java21.blog.backend.spam.repository.BannedWordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 블로그 만들기·조회·수정·삭제(T055, FR-010~012, FR-158·159, AS17~19). */
@ExtendWith(MockitoExtension.class)
class BlogServiceTest {

    @Mock
    private BannedWordRepository bannedWordRepository;

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Mock
    private BlogRepository blogRepository;
    @Mock
    private BlogQueryRepository blogQueryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private CategoryQueryRepository categoryQueryRepository;
    @Mock
    private MediaReferenceService mediaReferences;
    @Mock
    private net.java21.blog.backend.subscription.repository.BlogSubscriptionRepository subscriptionRepository;
    @Mock
    private net.java21.blog.backend.topic.repository.TopicRepository topicRepository;

    private BlogService service;
    private User owner;

    @BeforeEach
    void setUp() {
        BlogAccess access = new BlogAccess(blogRepository);
        service = new BlogService(blogRepository, blogQueryRepository, userRepository, access, new HandlePolicy(),
                passwordEncoder, new BlogsProperties(3), categoryQueryRepository, mediaReferences,
                subscriptionRepository,
                new net.java21.blog.backend.topic.service.TopicService(topicRepository, null, null, null, null, null, null),
                new BannedWordMatcher(bannedWordRepository), Clock.fixed(NOW, ZoneOffset.UTC));
        owner = TestEntities.user(1L, "marco@example.com", "$2a$hash", "마르코");
    }

    // ---- 만들기 ----

    @Test
    void createLocksMemberRowThenCountsThenInserts() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(2L);

        BlogResponse response = service.create(1L, new CreateBlogRequest("marco-dev", " 개발 노트 "));

        assertThat(response.handle()).isEqualTo("marco-dev");
        assertThat(response.title()).isEqualTo("개발 노트");
        assertThat(response.owner().nickname()).isEqualTo("마르코");
        assertThat(response.categories()).isEmpty();
        InOrder order = inOrder(userRepository, blogRepository);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(blogRepository).countByUserIdAndStatus(1L, BlogStatus.ACTIVE);
        order.verify(blogRepository).saveAndFlush(any(Blog.class));
    }

    @Test
    void bannedHandleTitleAndUpdatedTitleAreRejected() {
        when(bannedWordRepository.findAll()).thenReturn(java.util.List.of(new net.java21.blog.backend.spam.domain.BannedWord(
                owner, "casino", net.java21.blog.backend.spam.domain.BannedWordScope.NAME,
                net.java21.blog.backend.spam.domain.BannedWordAction.REJECT)));
        assertFieldError(() -> service.create(1L, new CreateBlogRequest("my-casino", "Casino Royale")),
                FieldError.of("handle", "BANNED_WORD"), FieldError.of("title", "BANNED_WORD"));
        verify(userRepository, never()).findByIdForUpdate(anyLong());

        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(TestEntities.blog(10L, owner, "marco")));
        UpdateBlogRequest title = new UpdateBlogRequest();
        title.setTitle("CA SI NO");
        assertFieldError(() -> service.update(1L, "marco", title), FieldError.of("title", "BANNED_WORD"));
    }

    @Test
    void createWithoutTitleUsesNicknameDefault() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        ArgumentCaptor<Blog> saved = ArgumentCaptor.forClass(Blog.class);

        service.create(1L, new CreateBlogRequest("marco-dev", null));

        verify(blogRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("마르코의 블로그");
        assertThat(saved.getValue().getUser()).isSameAs(owner);
        assertThat(saved.getValue().isCommentEnabled()).isTrue();
        assertThat(saved.getValue().getStatus()).isEqualTo(BlogStatus.ACTIVE);
    }

    @Test
    void createRejectedAtDefaultLimit() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(3L);

        expect(() -> service.create(1L, new CreateBlogRequest("marco-x", null)), ErrorCode.BLOG_LIMIT_EXCEEDED);
        verify(blogRepository, never()).saveAndFlush(any());
    }

    @Test
    void memberLimitOverridesDefault() {
        TestEntities.with(owner, "maxBlogs", 0);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(1L);
        expect(() -> service.create(1L, new CreateBlogRequest("marco-x", null)), ErrorCode.BLOG_LIMIT_EXCEEDED);

        TestEntities.with(owner, "maxBlogs", 5);
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(4L);
        assertThat(service.create(1L, new CreateBlogRequest("marco-x", null)).handle()).isEqualTo("marco-x");
    }

    @Test
    void createChecksHandleRulesBeforeLocking() {
        expect(() -> service.create(1L, new CreateBlogRequest("settings", null)), ErrorCode.HANDLE_RESERVED);
        expect(() -> service.create(1L, new CreateBlogRequest("x", null)), ErrorCode.HANDLE_INVALID);
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void createRejectsTakenHandleIncludingDeletedBlogs() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(blogRepository.existsByHandle("marco-life")).thenReturn(true);
        expect(() -> service.create(1L, new CreateBlogRequest("marco-life", null)), ErrorCode.HANDLE_TAKEN);
    }

    @Test
    void createMapsUniqueViolationToHandleTaken() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(blogRepository.saveAndFlush(any(Blog.class))).thenThrow(new DataIntegrityViolationException("dup"));
        expect(() -> service.create(1L, new CreateBlogRequest("marco-life", null)), ErrorCode.HANDLE_TAKEN);
    }

    @Test
    void inactiveOrMissingMemberCannotCreate() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        expect(() -> service.create(1L, new CreateBlogRequest("marco-x", null)), ErrorCode.UNAUTHENTICATED);
        TestEntities.with(owner, "status", UserStatus.SUSPENDED);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        expect(() -> service.create(1L, new CreateBlogRequest("marco-x", null)), ErrorCode.UNAUTHENTICATED);
    }

    // ---- 조회 ----

    @Test
    void getReturnsVisibleBlogWithOwner() {
        Blog blog = TestEntities.blog(10L, TestEntities.with(owner, "bio", "자바 개발자"), "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        List<CategoryNode> tree = List.of(new CategoryNode(1L, "Spring", 3,
                List.of(new CategoryNode(2L, "Boot", 2, List.of()))));
        when(categoryQueryRepository.findTree(10L)).thenReturn(tree);

        BlogResponse response = service.get("marco", null);

        assertThat(response.handle()).isEqualTo("marco");
        assertThat(response.title()).isEqualTo("마르코의 블로그");
        assertThat(response.description()).isNull();
        assertThat(response.coverImageUrl()).isNull();
        assertThat(response.commentEnabled()).isTrue();
        assertThat(response.owner()).isEqualTo(new BlogResponse.Owner("마르코", null, "자바 개발자"));
        assertThat(response.categories()).isEqualTo(tree);
        assertThat(response.subscriberCount()).isZero();
        assertThat(response.subscribedByMe()).isNull();
        assertThat(response.feedItemCount()).isEqualTo(20);
        assertThat(response.feedContentMode()).isEqualTo(net.java21.blog.backend.blog.domain.FeedContentMode.FULL);
        verify(subscriptionRepository, never()).existsByUserIdAndBlogId(any(), any());
    }

    /** 002 T023: 구독자 수·피드 설정과 요청한 회원의 구독 여부(비로그인 null, 구독 중 true, 아니면 false). */
    @Test
    void getCarriesSubscriberCountSubscribedByMeAndFeedSettings() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        TestEntities.with(blog, "subscriberCount", 7);
        blog.changeFeedSettings(50, net.java21.blog.backend.blog.domain.FeedContentMode.SUMMARY);
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(categoryQueryRepository.findTree(10L)).thenReturn(List.of());
        when(subscriptionRepository.existsByUserIdAndBlogId(2L, 10L)).thenReturn(true);
        when(subscriptionRepository.existsByUserIdAndBlogId(3L, 10L)).thenReturn(false);

        BlogResponse subscriber = service.get("marco", 2L);
        assertThat(subscriber.subscriberCount()).isEqualTo(7);
        assertThat(subscriber.subscribedByMe()).isTrue();
        assertThat(subscriber.feedItemCount()).isEqualTo(50);
        assertThat(subscriber.feedContentMode()).isEqualTo(net.java21.blog.backend.blog.domain.FeedContentMode.SUMMARY);
        assertThat(service.get("marco", 3L).subscribedByMe()).isFalse();
        assertThat(service.get("marco", null).subscribedByMe()).isNull();
    }

    @Test
    void missingOrDeletedBlogIsNotFound() {
        when(blogRepository.findByHandleWithOwner("nope")).thenReturn(Optional.empty());
        expect(() -> service.get("nope", null), ErrorCode.BLOG_NOT_FOUND);

        Blog deleted = TestEntities.blog(10L, owner, "gone");
        deleted.delete(NOW);
        when(blogRepository.findByHandleWithOwner("gone")).thenReturn(Optional.of(deleted));
        expect(() -> service.get("gone", 5L), ErrorCode.BLOG_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void blogOfSuspendedOrWithdrawnMemberIsNotFoundEvenForOwner(UserStatus status) {
        TestEntities.with(owner, "status", status);
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(TestEntities.blog(10L, owner, "marco")));
        // 005 FR-042: 블로그 첫 화면만 정지 회원이면 BLOG_RESTRICTED(탈퇴는 그대로 BLOG_NOT_FOUND).
        expect(() -> service.get("marco", null),
                status == UserStatus.SUSPENDED ? ErrorCode.BLOG_RESTRICTED : ErrorCode.BLOG_NOT_FOUND);
        expect(() -> service.update(1L, "marco", new UpdateBlogRequest()), ErrorCode.BLOG_NOT_FOUND);
    }

    @Test
    void myBlogsReturnsItemsCountAndEffectiveLimit() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        Instant created = Instant.parse("2026-10-01T00:00:00Z");
        when(blogQueryRepository.findMyBlogs(1L)).thenReturn(List.of(
                new MyBlogRow(10L, "marco", "첫째", null, 3, created),
                new MyBlogRow(11L, "marco-dev", "둘째", "k3Jd9fQ2xLmA7pZ0bR5tYw", 0, created)));

        MyBlogsResponse response = service.myBlogs(1L);

        assertThat(response.count()).isEqualTo(2);
        assertThat(response.limit()).isEqualTo(3);
        assertThat(response.items()).containsExactly(
                new MyBlogsResponse.Item("marco", "첫째", null, 3, created),
                new MyBlogsResponse.Item("marco-dev", "둘째", "/media/k3Jd9fQ2xLmA7pZ0bR5tYw", 0, created));

        TestEntities.with(owner, "maxBlogs", 1);
        assertThat(service.myBlogs(1L).limit()).isEqualTo(1);
    }

    @Test
    void handleAvailability() {
        when(blogRepository.existsByHandle("marco")).thenReturn(true);
        assertThat(service.handleAvailability("marco"))
                .isEqualTo(HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.TAKEN));
        assertThat(service.handleAvailability("Admin"))
                .isEqualTo(HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.RESERVED));
        assertThat(service.handleAvailability("a"))
                .isEqualTo(HandleAvailabilityResponse.unavailable(HandleAvailabilityResponse.Reason.INVALID));
        assertThat(service.handleAvailability("free-name")).isEqualTo(HandleAvailabilityResponse.ofAvailable());
    }

    // ---- 수정 ----

    @Test
    void patchChangesOnlySentFields() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        blog.changeDescription("원래 소개");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));

        UpdateBlogRequest request = new UpdateBlogRequest();
        request.setTitle(" 새 제목 ");
        request.setCommentEnabled(false);
        BlogResponse response = service.update(1L, "marco", request);

        assertThat(response.title()).isEqualTo("새 제목");
        assertThat(response.description()).isEqualTo("원래 소개");
        assertThat(response.commentEnabled()).isFalse();

        UpdateBlogRequest clear = new UpdateBlogRequest();
        clear.setDescription(null);
        assertThat(service.update(1L, "marco", clear).description()).isNull();
    }

    @Test
    void patchCannotClearTitleOrCommentSetting() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(TestEntities.blog(10L, owner, "marco")));
        UpdateBlogRequest title = new UpdateBlogRequest();
        title.setTitle(null);
        expectField(() -> service.update(1L, "marco", title), "title");
        UpdateBlogRequest comment = new UpdateBlogRequest();
        comment.setCommentEnabled(null);
        expectField(() -> service.update(1L, "marco", comment), "commentEnabled");
    }

    @Test
    void patchChangesFeedSettings() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));

        UpdateBlogRequest count = new UpdateBlogRequest();
        count.setFeedItemCount(30);
        BlogResponse counted = service.update(1L, "marco", count);
        assertThat(counted.feedItemCount()).isEqualTo(30);
        assertThat(counted.feedContentMode()).isEqualTo(FeedContentMode.FULL);

        UpdateBlogRequest mode = new UpdateBlogRequest();
        mode.setFeedContentMode("SUMMARY");
        BlogResponse summarized = service.update(1L, "marco", mode);
        assertThat(summarized.feedItemCount()).isEqualTo(30);
        assertThat(summarized.feedContentMode()).isEqualTo(FeedContentMode.SUMMARY);
        assertThat(blog.getFeedContentMode()).isEqualTo(FeedContentMode.SUMMARY);
    }

    @Test
    void patchRejectsFeedSettingsOutsideAllowedValues() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(TestEntities.blog(10L, owner, "marco")));
        UpdateBlogRequest fifteen = new UpdateBlogRequest();
        fifteen.setFeedItemCount(15);
        assertFieldError(() -> service.update(1L, "marco", fifteen),
                new FieldError("feedItemCount", "INVALID", Map.of("allowed", List.of(10, 20, 30, 50))));
        UpdateBlogRequest noCount = new UpdateBlogRequest();
        noCount.setFeedItemCount(null);
        assertFieldError(() -> service.update(1L, "marco", noCount), new FieldError("feedItemCount", "REQUIRED", Map.of()));
        UpdateBlogRequest both = new UpdateBlogRequest();
        both.setFeedContentMode("BOTH");
        assertFieldError(() -> service.update(1L, "marco", both),
                new FieldError("feedContentMode", "INVALID", Map.of("allowed", List.of("FULL", "SUMMARY"))));
        UpdateBlogRequest noMode = new UpdateBlogRequest();
        noMode.setFeedContentMode(null);
        assertFieldError(() -> service.update(1L, "marco", noMode),
                new FieldError("feedContentMode", "REQUIRED", Map.of()));
    }

    // ---- 포털 설정(003 T071, FR-077·089) ----

    @Test
    void getCarriesPortalSettings() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        blog.changePortalSettings(false, TestEntities.topic(31L, TestEntities.topic(3L, null, "knowledge"), "it"));
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(categoryQueryRepository.findTree(10L)).thenReturn(List.of());

        BlogResponse response = service.get("marco", null);

        assertThat(response.portalEnabled()).isFalse();
        assertThat(response.defaultTopicId()).isEqualTo(31L);
        assertThat(BlogResponse.of(TestEntities.blog(11L, owner, "new")).portalEnabled()).isTrue();
    }

    @Test
    void patchChangesPortalSettings() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        var major = TestEntities.topic(3L, null, "knowledge");
        var minor = TestEntities.topic(31L, major, "it");
        when(topicRepository.findWithParent(31L)).thenReturn(Optional.of(minor));

        UpdateBlogRequest request = new UpdateBlogRequest();
        request.setPortalEnabled(false);
        request.setDefaultTopicId(31L);
        BlogResponse response = service.update(1L, "marco", request);
        assertThat(response.portalEnabled()).isFalse();
        assertThat(response.defaultTopicId()).isEqualTo(31L);

        // 보내지 않은 값은 그대로, 같은 값은 검사 없이 통과(나중에 숨겨진 주제 유지)
        minor.hide();
        when(topicRepository.getReferenceById(31L)).thenReturn(minor);
        UpdateBlogRequest same = new UpdateBlogRequest();
        same.setDefaultTopicId(31L);
        assertThat(service.update(1L, "marco", same).portalEnabled()).isFalse();
        UpdateBlogRequest enable = new UpdateBlogRequest();
        enable.setPortalEnabled(true);
        BlogResponse enabled = service.update(1L, "marco", enable);
        assertThat(enabled.portalEnabled()).isTrue();
        assertThat(enabled.defaultTopicId()).isEqualTo(31L);

        UpdateBlogRequest clear = new UpdateBlogRequest();
        clear.setDefaultTopicId(null);
        assertThat(service.update(1L, "marco", clear).defaultTopicId()).isNull();
    }

    @Test
    void patchRejectsInvalidPortalSettings() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        var major = TestEntities.topic(3L, null, "knowledge");
        var hidden = TestEntities.topic(32L, major, "mobile");
        hidden.hide();
        when(topicRepository.findWithParent(3L)).thenReturn(Optional.of(major));
        when(topicRepository.findWithParent(32L)).thenReturn(Optional.of(hidden));
        when(topicRepository.findWithParent(99L)).thenReturn(Optional.empty());

        UpdateBlogRequest noFlag = new UpdateBlogRequest();
        noFlag.setPortalEnabled(null);
        assertFieldError(() -> service.update(1L, "marco", noFlag), new FieldError("portalEnabled", "REQUIRED", Map.of()));
        UpdateBlogRequest majorTopic = new UpdateBlogRequest();
        majorTopic.setDefaultTopicId(3L);
        expect(() -> service.update(1L, "marco", majorTopic), ErrorCode.TOPIC_NOT_SELECTABLE);
        UpdateBlogRequest hiddenTopic = new UpdateBlogRequest();
        hiddenTopic.setDefaultTopicId(32L);
        expect(() -> service.update(1L, "marco", hiddenTopic), ErrorCode.TOPIC_NOT_SELECTABLE);
        UpdateBlogRequest missing = new UpdateBlogRequest();
        missing.setDefaultTopicId(99L);
        expect(() -> service.update(1L, "marco", missing), ErrorCode.TOPIC_NOT_FOUND);
        assertThat(blog.isPortalEnabled()).isTrue();
        assertThat(blog.getDefaultTopic()).isNull();

        UpdateBlogRequest byOther = new UpdateBlogRequest();
        byOther.setPortalEnabled(false);
        expect(() -> service.update(2L, "marco", byOther), ErrorCode.FORBIDDEN);
    }

    // ---- 방명록·비회원 쓰기 설정(004 T011, FR-058·066) ----

    @Test
    void getAndPatchGuestSettings() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        assertThat(BlogResponse.of(blog).guestbookEnabled()).isTrue();
        assertThat(BlogResponse.of(blog).guestWriteEnabled()).isFalse();

        UpdateBlogRequest both = new UpdateBlogRequest();
        both.setGuestbookEnabled(false);
        both.setGuestWriteEnabled(true);
        BlogResponse response = service.update(1L, "marco", both);
        assertThat(response.guestbookEnabled()).isFalse();
        assertThat(response.guestWriteEnabled()).isTrue();

        // 보내지 않은 값은 그대로
        UpdateBlogRequest onlyGuestbook = new UpdateBlogRequest();
        onlyGuestbook.setGuestbookEnabled(true);
        response = service.update(1L, "marco", onlyGuestbook);
        assertThat(response.guestbookEnabled()).isTrue();
        assertThat(response.guestWriteEnabled()).isTrue();
        UpdateBlogRequest title = new UpdateBlogRequest();
        title.setTitle("새 제목");
        assertThat(service.update(1L, "marco", title).guestWriteEnabled()).isTrue();
    }

    @Test
    void patchCannotClearGuestSettingsAndOnlyOwnerChangesThem() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));

        UpdateBlogRequest noGuestbook = new UpdateBlogRequest();
        noGuestbook.setGuestbookEnabled(null);
        assertFieldError(() -> service.update(1L, "marco", noGuestbook),
                new FieldError("guestbookEnabled", "REQUIRED", Map.of()));
        UpdateBlogRequest noGuestWrite = new UpdateBlogRequest();
        noGuestWrite.setGuestWriteEnabled(null);
        assertFieldError(() -> service.update(1L, "marco", noGuestWrite),
                new FieldError("guestWriteEnabled", "REQUIRED", Map.of()));
        UpdateBlogRequest byOther = new UpdateBlogRequest();
        byOther.setGuestWriteEnabled(true);
        expect(() -> service.update(2L, "marco", byOther), ErrorCode.FORBIDDEN);
        assertThat(blog.isGuestbookEnabled()).isTrue();
        assertThat(blog.isGuestWriteEnabled()).isFalse();
    }

    @Test
    void onlyOwnerCanPatch() {
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(TestEntities.blog(10L, owner, "marco")));
        expect(() -> service.update(2L, "marco", new UpdateBlogRequest()), ErrorCode.FORBIDDEN);
    }

    // ---- 삭제 ----

    @Test
    void deleteMarksBlogDeletedAndMovesPostsToTrashInsideMemberLock() {
        Blog blog = TestEntities.blog(10L, owner, "marco-life");
        when(blogRepository.findByHandleWithOwner("marco-life")).thenReturn(Optional.of(blog));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches("password1", "$2a$hash")).thenReturn(true);
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(2L);

        service.delete(1L, "marco-life", "password1");

        assertThat(blog.getStatus()).isEqualTo(BlogStatus.DELETED);
        assertThat(blog.getDeletedAt()).isEqualTo(NOW);
        InOrder order = inOrder(userRepository, blogRepository, blogQueryRepository);
        order.verify(userRepository).findByIdForUpdate(1L);
        order.verify(blogRepository).countByUserIdAndStatus(1L, BlogStatus.ACTIVE);
        order.verify(blogQueryRepository).moveAllPostsToTrash(10L, NOW);
    }

    @Test
    void lastBlogCannotBeDeleted() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches("password1", "$2a$hash")).thenReturn(true);
        when(blogRepository.countByUserIdAndStatus(1L, BlogStatus.ACTIVE)).thenReturn(1L);

        expect(() -> service.delete(1L, "marco", "password1"), ErrorCode.LAST_BLOG_CANNOT_BE_DELETED);
        assertThat(blog.isActive()).isTrue();
        verify(blogQueryRepository, never()).moveAllPostsToTrash(anyLong(), any());
    }

    @Test
    void wrongPasswordIsCurrentPasswordMismatch() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(owner));
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        expect(() -> service.delete(1L, "marco", "wrong"), ErrorCode.CURRENT_PASSWORD_MISMATCH);
        expect(() -> service.delete(1L, "marco", null), ErrorCode.CURRENT_PASSWORD_MISMATCH);
        assertThat(blog.isActive()).isTrue();
    }

    @Test
    void onlyOwnerCanDeleteAndDeletedBlogIsNotFound() {
        Blog blog = TestEntities.blog(10L, owner, "marco");
        when(blogRepository.findByHandleWithOwner("marco")).thenReturn(Optional.of(blog));
        expect(() -> service.delete(2L, "marco", "password1"), ErrorCode.FORBIDDEN);

        blog.delete(NOW);
        expect(() -> service.delete(1L, "marco", "password1"), ErrorCode.BLOG_NOT_FOUND);
        verify(userRepository, never()).findByIdForUpdate(anyLong());
    }

    private static void expect(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private static void assertFieldError(Runnable action, FieldError... expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).containsExactly(expected);
                });
    }

    private static void expectField(Runnable action, String field) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly(field);
                });
    }
}
