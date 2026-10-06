package net.java21.blog.backend.user.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import net.java21.blog.backend.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailHash(String emailHash);

    boolean existsByEmailHash(String emailHash);

    /**
     * 회원 행을 {@code SELECT ... FOR UPDATE}로 잠근다. 블로그 만들기·삭제의 한도·마지막 블로그 확인을 회원 단위로 줄 세운다(R28).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
