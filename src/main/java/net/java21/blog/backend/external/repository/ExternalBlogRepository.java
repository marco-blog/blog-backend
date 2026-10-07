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

    /** 회원의 등록 전부(탈퇴, research E16). */
    List<ExternalBlog> findByMemberId(Long userId);
}
