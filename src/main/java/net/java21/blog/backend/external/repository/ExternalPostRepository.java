package net.java21.blog.backend.external.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.external.domain.ExternalPost;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.domain.RemovedReason;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 외부 글(007). 포털·목록 조회는 QueryDSL 리포지토리. */
public interface ExternalPostRepository extends JpaRepository<ExternalPost, Long> {

    /** 링크 점검 대상 한 줄(id·원문 링크). */
    record LinkTarget(Long id, String link) {
    }

    /**
     * 링크 점검 대상(research E12): ACTIVE 글 중 점검한 적이 없거나 {@code checkedBefore} 이전에 점검한 글, 오래 점검하지 않은 순
     * (없음이 먼저, 같으면 id). 쿼리 1회.
     */
    @Query("select new net.java21.blog.backend.external.repository.ExternalPostRepository$LinkTarget(p.id, p.link)"
            + " from ExternalPost p where p.status = :active"
            + " and (p.linkCheckedAt is null or p.linkCheckedAt < :checkedBefore)"
            + " order by case when p.linkCheckedAt is null then 0 else 1 end, p.linkCheckedAt asc, p.id asc")
    List<LinkTarget> findLinkCheckTargets(@Param("active") ExternalPostStatus active,
            @Param("checkedBefore") Instant checkedBefore, org.springframework.data.domain.Limit limit);

    /** 같은 글 찾기(research E5): guid 해시 IN 1회. */
    @Query("select p from ExternalPost p where p.externalBlog.id = :blogId and p.guidHash in :hashes")
    List<ExternalPost> findByGuidHashes(@Param("blogId") Long blogId, @Param("hashes") Collection<String> hashes);

    /** 같은 글 찾기(research E5): 링크 해시 IN 1회. */
    @Query("select p from ExternalPost p where p.externalBlog.id = :blogId and p.linkHash in :hashes")
    List<ExternalPost> findByLinkHashes(@Param("blogId") Long blogId, @Param("hashes") Collection<String> hashes);

    /** 썸네일 키의 글(블로그 함께). */
    @Query("select p from ExternalPost p join fetch p.externalBlog where p.thumbnailKey = :key")
    Optional<ExternalPost> findByThumbnailKeyWithBlog(@Param("key") String key);

    /** 소급 대상(research E7): 최근 발행·ACTIVE·이미지 주소 있음·썸네일 없음. */
    @Query("select p.id from ExternalPost p where p.externalBlog.id = :blogId and p.status = :active"
            + " and p.imageUrl is not null and p.thumbnailKey is null and p.publishedAt >= :since"
            + " order by p.publishedAt desc, p.id desc")
    List<Long> findBackfillIds(@Param("blogId") Long blogId, @Param("active") ExternalPostStatus active,
            @Param("since") Instant since, org.springframework.data.domain.Limit limit);

    @Query("select p from ExternalPost p join fetch p.externalBlog where p.id = :id")
    Optional<ExternalPost> findWithBlog(@Param("id") Long id);

    /** 블로그의 ACTIVE 글을 모두 내린다(차단·탈퇴). @return 바뀐 행 수 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ExternalPost p set p.status = :removed, p.removedReason = :reason, p.updatedAt = :now"
            + " where p.externalBlog.id in :blogIds and p.status = :active")
    int removeActive(@Param("blogIds") Collection<Long> blogIds, @Param("reason") RemovedReason reason,
            @Param("now") Instant now, @Param("removed") ExternalPostStatus removed,
            @Param("active") ExternalPostStatus active);

    default int removeAllActive(Collection<Long> blogIds, RemovedReason reason, Instant now) {
        if (blogIds.isEmpty()) {
            return 0;
        }
        return removeActive(blogIds, reason, now, ExternalPostStatus.REMOVED, ExternalPostStatus.ACTIVE);
    }

    /**
     * 같은 피드의 해제된 등록에 남은 글(탈퇴로 내린 글 제외)을 새 등록으로 옮긴다(결정 표 24번). @return 옮긴 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE external_posts SET external_blog_id = :toBlogId, updated_at = :now"
            + " WHERE external_blog_id IN (SELECT b.id FROM external_blogs b WHERE b.feed_url_hash = :hash"
            + " AND b.status = 'RELEASED' AND b.id <> :toBlogId)"
            + " AND (removed_reason IS NULL OR removed_reason <> 'MEMBER_WITHDRAWN')", nativeQuery = true)
    int moveKeptPosts(@Param("hash") String feedUrlHash, @Param("toBlogId") Long toBlogId, @Param("now") Instant now);

    /** 블로그 글의 id(삭제 전 썸네일 키·제외 행 정리). */
    @Query("select p.id from ExternalPost p where p.externalBlog.id = :blogId")
    List<Long> findIdsByBlog(@Param("blogId") Long blogId);

    @Query("select p.thumbnailKey from ExternalPost p where p.externalBlog.id = :blogId and p.thumbnailKey is not null")
    List<String> findThumbnailKeysByBlog(@Param("blogId") Long blogId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ExternalPost p where p.id in :ids")
    int deleteByIds(@Param("ids") Collection<Long> ids);

    long countByExternalBlogIdAndStatus(Long blogId, ExternalPostStatus status);

    /** 등록의 글(발행 최신순). {@code status}가 null이면 전체. */
    @Query(value = "select p from ExternalPost p where p.externalBlog.id = :blogId"
            + " and (:status is null or p.status = :status) order by p.publishedAt desc, p.id desc",
            countQuery = "select count(p) from ExternalPost p where p.externalBlog.id = :blogId"
                    + " and (:status is null or p.status = :status)")
    org.springframework.data.domain.Page<ExternalPost> findPage(@Param("blogId") Long blogId,
            @Param("status") ExternalPostStatus status, org.springframework.data.domain.Pageable pageable);

    /** 클릭 수 1 증가(쿼리 1회). */
    @Modifying
    @Query("update ExternalPost p set p.clickCount = p.clickCount + 1 where p.id = :id")
    int incrementClick(@Param("id") Long id);

    /** 썸네일 키가 DB에 있는지(정리 작업). */
    @Query("select p.thumbnailKey from ExternalPost p where p.thumbnailKey in :keys")
    List<String> findExistingThumbnailKeys(@Param("keys") Collection<String> keys);
}
