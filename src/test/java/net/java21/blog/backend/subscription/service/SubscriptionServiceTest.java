package net.java21.blog.backend.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.subscription.dto.FeedPostResponse;
import net.java21.blog.backend.subscription.dto.SubscriptionStateResponse;
import net.java21.blog.backend.subscription.event.BlogSubscribedEvent;
import net.java21.blog.backend.subscription.repository.BlogSubscriptionRepository;
import net.java21.blog.backend.subscription.repository.FeedPostRow;
import net.java21.blog.backend.subscription.repository.SubscriptionFeedQueryRepository;
import net.java21.blog.backend.support.MutableClock;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 구독 규칙(T021, FR-031·032, AS2·3): 없는·삭제·주인 정지·탈퇴 블로그 404, 내 블로그 422, 이미 구독이면 같은 응답·이벤트 없음,
 * 새로 구독했을 때만 {@link BlogSubscribedEvent}, 취소는 삭제·정지 블로그에도 되고 없는 handle은 404, 응답 {@code { handle, subscribed, subscriberCount }}.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final long OWNER = 1L;
    private static final long READER = 2L;

    @Mock
    private BlogRepository blogRepository;
    @Mock
    private BlogSubscriptionRepository subscriptionRepository;
    @Mock
    private SubscriptionFeedQueryRepository feedQueryRepository;
    @Mock
    private TagQueryRepository tagQueryRepository;
    @Mock
    private ApplicationEventPublisher events;

    private SubscriptionService service;
    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        service = new SubscriptionService(blogRepository, subscriptionRepository, feedQueryRepository,
                tagQueryRepository, events, new MutableClock(NOW));
        owner = TestEntities.user(OWNER);
        blog = TestEntities.blog(10L, owner, "marco");
    }

    @Test
    void subscribeInsertsIncrementsAndPublishesEvent() {
        found(blog);
        when(subscriptionRepository.lockSubscriberCount(10L)).thenReturn(Optional.of(2));
        when(subscriptionRepository.insertIgnore(READER, 10L, NOW)).thenReturn(1);

        assertThat(service.subscribe(READER, "marco")).isEqualTo(new SubscriptionStateResponse("marco", true, 3));
        verify(subscriptionRepository).changeSubscriberCount(10L, 1);
        verify(events).publishEvent(new BlogSubscribedEvent(READER, 10L));
    }

    @Test
    void subscribingAgainAnswersTheSameWithoutEvent() {
        found(blog);
        when(subscriptionRepository.lockSubscriberCount(10L)).thenReturn(Optional.of(3));
        when(subscriptionRepository.insertIgnore(READER, 10L, NOW)).thenReturn(0);

        assertThat(service.subscribe(READER, "marco")).isEqualTo(new SubscriptionStateResponse("marco", true, 3));
        verify(subscriptionRepository, never()).changeSubscriberCount(anyLong(), anyInt());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void ownBlogCannotBeSubscribed() {
        found(blog);
        expect(() -> service.subscribe(OWNER, "marco"), ErrorCode.CANNOT_SUBSCRIBE_OWN_BLOG);

        Blog second = TestEntities.blog(11L, owner, "marco-dev");
        found(second);
        expect(() -> service.subscribe(OWNER, "marco-dev"), ErrorCode.CANNOT_SUBSCRIBE_OWN_BLOG);
        verify(subscriptionRepository, never()).insertIgnore(anyLong(), anyLong(), any());
    }

    @Test
    void missingOrDeletedBlogIsNotFound() {
        when(blogRepository.findByHandleWithOwner("nope")).thenReturn(Optional.empty());
        expect(() -> service.subscribe(READER, "nope"), ErrorCode.BLOG_NOT_FOUND);

        blog.delete(NOW);
        found(blog);
        expect(() -> service.subscribe(READER, "marco"), ErrorCode.BLOG_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "WITHDRAWN"})
    void blogOfSuspendedOrWithdrawnOwnerIsNotFound(UserStatus status) {
        TestEntities.with(owner, "status", status);
        found(blog);
        expect(() -> service.subscribe(READER, "marco"), ErrorCode.BLOG_NOT_FOUND);
    }

    @Test
    void unsubscribeDeletesAndDecrementsEvenForDeletedBlogs() {
        blog.delete(NOW);
        found(blog);
        when(subscriptionRepository.lockSubscriberCount(10L)).thenReturn(Optional.of(3));
        when(subscriptionRepository.delete(READER, 10L)).thenReturn(1);

        assertThat(service.unsubscribe(READER, "marco")).isEqualTo(new SubscriptionStateResponse("marco", false, 2));
        verify(subscriptionRepository).changeSubscriberCount(10L, -1);
    }

    @Test
    void unsubscribeWhenNotSubscribedIsStillOk() {
        TestEntities.with(owner, "status", UserStatus.SUSPENDED);
        found(blog);
        when(subscriptionRepository.lockSubscriberCount(10L)).thenReturn(Optional.of(0));
        when(subscriptionRepository.delete(READER, 10L)).thenReturn(0);

        assertThat(service.unsubscribe(READER, "marco")).isEqualTo(new SubscriptionStateResponse("marco", false, 0));
        verify(subscriptionRepository, never()).changeSubscriberCount(anyLong(), anyInt());
    }

    @Test
    void unsubscribeOfUnknownHandleIsNotFound() {
        when(blogRepository.findByHandleWithOwner("nope")).thenReturn(Optional.empty());
        expect(() -> service.unsubscribe(READER, "nope"), ErrorCode.BLOG_NOT_FOUND);
    }

    @Test
    void isSubscribedByIsNullForAnonymous() {
        assertThat(service.isSubscribedBy(null, 10L)).isNull();
        when(subscriptionRepository.existsByUserIdAndBlogId(READER, 10L)).thenReturn(true);
        assertThat(service.isSubscribedBy(READER, 10L)).isTrue();
    }

    @Test
    void feedMapsRowsWithTagsBlogAndAuthorInOneTagQuery() {
        Instant at = Instant.parse("2026-10-05T00:00:00Z");
        FeedPostRow publicRow = new FeedPostRow(5L, "글", "요약", "/media/t", 3L, "Spring", 10, 2,
                PostVisibility.PUBLIC, PostStatus.PUBLISHED, at, at, "marco", "마르코의 블로그", "마르코", "pk");
        // 본문 노출 가능이 아닌 공개 범위(004 보호 글 자리, 결정 11): 002에는 PROTECTED 값이 없어 PUBLIC이 아닌 값으로 확인한다.
        FeedPostRow protectedRow = new FeedPostRow(6L, "보호 글", "요약", "/media/t", null, null, 0, 0,
                PostVisibility.PRIVATE, PostStatus.PUBLISHED, at, at, "third", "셋째", "셋", null);
        PageRequest pageable = PageRequest.of(0, 20);
        when(feedQueryRepository.findFeed(READER, pageable))
                .thenReturn(new PageImpl<>(List.of(publicRow, protectedRow), pageable, 2));
        when(tagQueryRepository.findTagNames(List.of(5L, 6L))).thenReturn(Map.of(5L, List.of("java")));

        List<FeedPostResponse> feed = service.feed(READER, pageable).getContent();

        FeedPostResponse first = feed.getFirst();
        assertThat(first.tags()).containsExactly("java");
        assertThat(first.category().name()).isEqualTo("Spring");
        assertThat(first.blog()).isEqualTo(new FeedPostResponse.BlogRef("marco", "마르코의 블로그"));
        assertThat(first.author()).isEqualTo(new FeedPostResponse.Author("마르코", "/media/pk"));
        assertThat(first.hasDraft()).isFalse();
        assertThat(first.summary()).isEqualTo("요약");
        FeedPostResponse second = feed.get(1);
        assertThat(second.tags()).isEmpty();
        assertThat(second.category()).isNull();
        assertThat(second.summary()).isNull();
        assertThat(second.thumbnailUrl()).isNull();
        assertThat(second.author().profileImageUrl()).isNull();
    }

    private void found(Blog b) {
        when(blogRepository.findByHandleWithOwner(b.getHandle())).thenReturn(Optional.of(b));
    }

    private static void expect(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
