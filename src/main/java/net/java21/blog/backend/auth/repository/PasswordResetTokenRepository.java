package net.java21.blog.backend.auth.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import net.java21.blog.backend.auth.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** 행을 잠가 같은 링크의 동시 사용을 줄 세운다(한 번만 사용). 회원을 함께 읽는다(쿼리 1회). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t join fetch t.user where t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
