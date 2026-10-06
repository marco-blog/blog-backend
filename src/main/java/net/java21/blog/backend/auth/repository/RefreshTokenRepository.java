package net.java21.blog.backend.auth.repository;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import net.java21.blog.backend.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** 토큰 해시로 찾으면서 회원을 함께 읽는다(쿼리 1회). */
    @Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHash(@Param("tokenHash") String tokenHash);

    /**
     * 회전용: 행을 잠가 같은 토큰의 동시 리프레시를 줄 세운다. 뒤에 온 요청은 앞 요청이 커밋한 사용 처리를 보고
     * 유예 규칙(방금 발급한 토큰 반환)을 따른다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** 로그인 계열 전체 폐기(로그아웃, 재사용 감지). UPDATE 1회. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") String familyId, @Param("now") Instant now);

    /** 회원의 모든 계열 폐기(정지, 탈퇴, 비밀번호 재설정). UPDATE 1회. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.user.id = :userId and t.revokedAt is null")
    int revokeAllByUserId(@Param("userId") Long userId, @Param("now") Instant now);
}
