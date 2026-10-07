package net.java21.blog.backend.external.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 외부 블로그 등록(007). 목록·수집 선택은 {@link ExternalBlogQueryRepository}. */
public interface ExternalBlogRepository extends JpaRepository<ExternalBlog, Long> {

    /** 같은 피드의 거절·해제가 아닌 등록(피드당 많아야 하나, FR-112). */
    @Query("select b from ExternalBlog b where b.feedUrlHash = :hash and b.status not in :excluded")
    Optional<ExternalBlog> findHolding(@Param("hash") String feedUrlHash,
            @Param("excluded") Collection<ExternalBlogStatus> excluded);

    default Optional<ExternalBlog> findHolding(String feedUrlHash) {
        return findHolding(feedUrlHash, ExternalBlogStatus.NOT_COUNTED);
    }

    /** 같은 피드의 해제된 등록들. */
    List<ExternalBlog> findByFeedUrlHashAndStatus(String feedUrlHash, ExternalBlogStatus status);

    /** 회원 한도에서 세는 등록 수(REJECTED·RELEASED 제외, research E8). */
    @Query("select count(b) from ExternalBlog b where b.member.id = :userId and b.status not in :excluded")
    long countHeld(@Param("userId") Long userId, @Param("excluded") Collection<ExternalBlogStatus> excluded);

    default long countCounted(Long userId) {
        return countHeld(userId, ExternalBlogStatus.NOT_COUNTED);
    }

    /** 같은 피드의 가장 최근 등록(상태 무관). 인증 확인 때 피드 주소를 찾는다. */
    Optional<ExternalBlog> findFirstByFeedUrlHashOrderByIdDesc(String feedUrlHash);

    /**
     * 수집 성공(200)을 한 번에 기록한다(research E5, 쿼리 수 고정). ACTIVE가 아니게 됐으면 0행 — 호출한 쪽은 글을 저장하지 않는다.
     * 이름·사이트 주소는 비어 있을 때만 채운다.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update ExternalBlog b set b.lastFetchedAt = :now, b.lastSuccessAt = :now, b.lastFetchResult = :result,"
            + " b.lastHttpStatus = :http, b.etag = :etag, b.lastModified = :lastModified, b.consecutiveFailures = 0,"
            + " b.firstFailedAt = null, b.nextFetchAt = :next,"
            + " b.title = case when (b.title is null or b.title = '') then :title else b.title end,"
            + " b.siteUrl = case when (b.siteUrl is null or b.siteUrl = '') then :siteUrl else b.siteUrl end,"
            + " b.feedFormat = :format, b.updatedAt = :now"
            + " where b.id = :id and b.status = :active")
    int recordFetched(@Param("id") Long id, @Param("active") ExternalBlogStatus active,
            @Param("result") net.java21.blog.backend.external.domain.FetchResultCode result,
            @Param("http") Integer httpStatus, @Param("etag") String etag, @Param("lastModified") String lastModified,
            @Param("title") String title, @Param("siteUrl") String siteUrl,
            @Param("format") net.java21.blog.backend.external.feed.FeedFormat format, @Param("now") java.time.Instant now,
            @Param("next") java.time.Instant next);

    /** 수집 결과 304(변경 없음). ETag·Last-Modified는 그대로. */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update ExternalBlog b set b.lastFetchedAt = :now, b.lastSuccessAt = :now, b.lastFetchResult = :result,"
            + " b.lastHttpStatus = 304, b.consecutiveFailures = 0, b.firstFailedAt = null, b.nextFetchAt = :next,"
            + " b.updatedAt = :now where b.id = :id and b.status = :active")
    int recordNotModified(@Param("id") Long id, @Param("active") ExternalBlogStatus active,
            @Param("result") net.java21.blog.backend.external.domain.FetchResultCode result,
            @Param("now") java.time.Instant now, @Param("next") java.time.Instant next);

    /** 회원의 등록 전부(탈퇴, research E16). */
    List<ExternalBlog> findByMemberId(Long userId);
}
