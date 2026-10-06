package net.java21.blog.backend.subscription.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

import net.java21.blog.backend.subscription.domain.BlogSubscription;
import net.java21.blog.backend.subscription.domain.BlogSubscriptionId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 구독 쓰기와 정리(002 research D1). 좋아요와 같이 {@code INSERT IGNORE}·DELETE의 영향 행 수와 원자적 UPDATE만 쓴다.
 * 카운터 UPDATE는 {@code updated_at = updated_at}으로 블로그의 수정 시각(피드 ETag)을 바꾸지 않는다("구현 전 결정 사항" 15번).
 */
public interface BlogSubscriptionRepository extends JpaRepository<BlogSubscription, BlogSubscriptionId> {

    /** @return 새로 넣었으면 1, 이미 있었으면 0 */
    @Modifying
    @Query(value = "INSERT IGNORE INTO blog_subscriptions (user_id, blog_id, created_at) VALUES (:userId, :blogId, :now)",
            nativeQuery = true)
    int insertIgnore(@Param("userId") Long userId, @Param("blogId") Long blogId, @Param("now") Instant now);

    /** @return 지웠으면 1, 없었으면 0 */
    @Modifying
    @Query("delete from BlogSubscription s where s.id.userId = :userId and s.id.blogId = :blogId")
    int delete(@Param("userId") Long userId, @Param("blogId") Long blogId);

    /** 블로그의 구독자 수를 원자적으로 바꾼다(UPDATE 1회, 0 아래로 내려가지 않음). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Blog b set b.subscriberCount = case when b.subscriberCount + :delta < 0 then 0"
            + " else b.subscriberCount + :delta end, b.updatedAt = b.updatedAt where b.id = :blogId")
    int changeSubscriberCount(@Param("blogId") Long blogId, @Param("delta") int delta);

    /**
     * 블로그의 구독자 수를 읽으며 블로그 행을 잠근다({@code SELECT ... FOR UPDATE}). 같은 블로그의 구독·취소를 줄 세워 외래 키 공유 잠금으로 인한
     * 교착 상태를 막고 응답의 수를 다시 읽지 않게 한다. 블로그가 없으면 빈 값.
     */
    @Query(value = "SELECT subscriber_count FROM blogs WHERE id = :blogId FOR UPDATE", nativeQuery = true)
    Optional<Integer> lockSubscriberCount(@Param("blogId") Long blogId);

    boolean existsByUserIdAndBlogId(Long userId, Long blogId);

    /**
     * 회원 탈퇴(결정 3): 그 회원이 구독한 블로그들의 구독자 수를 1씩 줄인다(UPDATE 1회, 블로그 수와 무관). {@link #deleteAllByUser}보다 먼저 부른다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Blog b set b.subscriberCount = case when b.subscriberCount > 0 then b.subscriberCount - 1"
            + " else 0 end, b.updatedAt = b.updatedAt"
            + " where b.id in (select s.id.blogId from BlogSubscription s where s.id.userId = :userId)")
    int decrementSubscriberCountsOf(@Param("userId") Long userId);

    /** 회원의 구독 행을 모두 지운다(DELETE 1회). @return 지운 행 수 */
    @Modifying
    @Query("delete from BlogSubscription s where s.id.userId = :userId")
    int deleteAllByUser(@Param("userId") Long userId);

    /** 블로그 정리(삭제 후 보관 기간 지남): 그 블로그들의 구독 행을 지운다(DELETE 1회). */
    @Modifying
    @Query("delete from BlogSubscription s where s.id.blogId in :blogIds")
    int deleteByBlogIds(@Param("blogIds") Collection<Long> blogIds);
}
