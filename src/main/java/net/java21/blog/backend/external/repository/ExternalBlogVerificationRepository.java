package net.java21.blog.backend.external.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.external.domain.ExternalBlogVerification;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 소유 인증 코드(007 FR-110). */
public interface ExternalBlogVerificationRepository extends JpaRepository<ExternalBlogVerification, Long> {

    /** 같은 회원·피드의 아직 유효한 코드(최근 것 먼저). */
    @Query("select v from ExternalBlogVerification v where v.user.id = :userId and v.feedUrlHash = :hash"
            + " and v.expiresAt > :now order by v.createdAt desc, v.id desc")
    List<ExternalBlogVerification> findValid(@Param("userId") Long userId, @Param("hash") String feedUrlHash,
            @Param("now") Instant now, Limit limit);

    default Optional<ExternalBlogVerification> findValid(Long userId, String feedUrlHash, Instant now) {
        return findValid(userId, feedUrlHash, now, Limit.of(1)).stream().findFirst();
    }

    boolean existsByCode(String code);

    /** 만료가 {@code before}보다 오래된 코드를 지운다(research E15). 등록 연결은 끊기지 않는다(외부 블로그에 결과가 있음). */
    @Modifying
    @Query(value = "DELETE FROM external_blog_verifications WHERE expires_at < :before LIMIT :limit", nativeQuery = true)
    int deleteExpiredBefore(@Param("before") Instant before, @Param("limit") int limit);
}
