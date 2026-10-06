package net.java21.blog.backend.subscription.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.subscription.dto.FeedPostResponse;
import net.java21.blog.backend.subscription.dto.SubscriptionStateResponse;
import net.java21.blog.backend.subscription.event.BlogSubscribedEvent;
import net.java21.blog.backend.subscription.repository.BlogSubscriptionRepository;
import net.java21.blog.backend.subscription.repository.FeedPostRow;
import net.java21.blog.backend.subscription.repository.SubscriptionFeedQueryRepository;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 구독과 구독 피드(002 FR-031·032, contracts/api.md 구독 절, research D1·D2).
 * <ul>
 *   <li>구독: 블로그와 주인이 ACTIVE여야 한다(아니면 404 {@code BLOG_NOT_FOUND}). 내가 가진 블로그면 422 {@code CANNOT_SUBSCRIBE_OWN_BLOG}.
 *       이미 구독 중이면 같은 응답이고 이벤트는 없다. 새로 구독했을 때만 {@link BlogSubscribedEvent}(커밋 뒤 NEW_SUBSCRIBER 알림).</li>
 *   <li>취소: handle이 있는 블로그면 상태와 관계없이 된다(삭제·정지 포함). 없는 handle은 404.</li>
 *   <li>행 추가·삭제와 {@code blogs.subscriber_count} ±1은 한 트랜잭션.</li>
 * </ul>
 */
@Service
public class SubscriptionService {

    private final BlogRepository blogRepository;
    private final BlogSubscriptionRepository subscriptionRepository;
    private final SubscriptionFeedQueryRepository feedQueryRepository;
    private final TagQueryRepository tagQueryRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public SubscriptionService(BlogRepository blogRepository, BlogSubscriptionRepository subscriptionRepository,
            SubscriptionFeedQueryRepository feedQueryRepository, TagQueryRepository tagQueryRepository,
            ApplicationEventPublisher events, Clock clock) {
        this.blogRepository = blogRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.feedQueryRepository = feedQueryRepository;
        this.tagQueryRepository = tagQueryRepository;
        this.events = events;
        this.clock = clock;
    }

    /** 쿼리: 블로그(주인) 1 + 잠금·수 1 + INSERT 1 + 새로 구독했으면 UPDATE 1. */
    @Transactional
    public SubscriptionStateResponse subscribe(long userId, String handle) {
        Blog blog = blogRepository.findByHandleWithOwner(handle)
                .filter(b -> b.isActive() && b.getUser().isActive())
                .orElseThrow(() -> blogNotFound(handle));
        if (blog.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.CANNOT_SUBSCRIBE_OWN_BLOG, "Cannot subscribe to own blog: " + handle);
        }
        int count = subscriptionRepository.lockSubscriberCount(blog.getId()).orElseThrow(() -> blogNotFound(handle));
        if (subscriptionRepository.insertIgnore(userId, blog.getId(), clock.instant()) == 1) {
            subscriptionRepository.changeSubscriberCount(blog.getId(), 1);
            count++;
            events.publishEvent(new BlogSubscribedEvent(userId, blog.getId()));
        }
        return new SubscriptionStateResponse(blog.getHandle(), true, count);
    }

    /** 쿼리: 블로그 1 + 잠금·수 1 + DELETE 1 + 지웠으면 UPDATE 1. */
    @Transactional
    public SubscriptionStateResponse unsubscribe(long userId, String handle) {
        Blog blog = blogRepository.findByHandleWithOwner(handle).orElseThrow(() -> blogNotFound(handle));
        int count = subscriptionRepository.lockSubscriberCount(blog.getId()).orElseThrow(() -> blogNotFound(handle));
        if (subscriptionRepository.delete(userId, blog.getId()) == 1) {
            subscriptionRepository.changeSubscriberCount(blog.getId(), -1);
            count = Math.max(0, count - 1);
        }
        return new SubscriptionStateResponse(blog.getHandle(), false, count);
    }

    /** 이 회원이 이 블로그를 구독 중인지. 비로그인이면 null. 쿼리 0~1회. */
    @Transactional(readOnly = true)
    public Boolean isSubscribedBy(Long userId, Long blogId) {
        return userId == null ? null : subscriptionRepository.existsByUserIdAndBlogId(userId, blogId);
    }

    /** 구독 피드. 쿼리 3회(목록, 전체 수, 태그) — 구독 수·글 수와 무관. */
    @Transactional(readOnly = true)
    public Page<FeedPostResponse> feed(long userId, Pageable pageable) {
        Page<FeedPostRow> rows = feedQueryRepository.findFeed(userId, pageable);
        Map<Long, List<String>> tags = tagQueryRepository.findTagNames(
                rows.getContent().stream().map(FeedPostRow::id).toList());
        return rows.map(row -> FeedPostResponse.of(row, tags.get(row.id())));
    }

    private static BusinessException blogNotFound(String handle) {
        return new BusinessException(ErrorCode.BLOG_NOT_FOUND, "Blog not found: " + handle);
    }
}
