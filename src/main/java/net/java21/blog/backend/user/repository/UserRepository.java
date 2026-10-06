package net.java21.blog.backend.user.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import net.java21.blog.backend.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailHash(String emailHash);

    boolean existsByEmailHash(String emailHash);

    /**
     * 회원 행을 {@code SELECT ... FOR UPDATE}로 잠근다. 블로그 만들기·삭제의 한도·마지막 블로그 확인을 회원 단위로 줄 세운다(R28).
     */
    /**
     * 마지막 확인 릴리스 노트 버전을 읽은 값({@code old}, null 가능)일 때만 바꾼다(003 FR-163). 그 사이 다른 요청이 더 높은 버전을 저장했으면
     * 0을 돌려주고, 서비스가 다시 읽어 비교한다(낮은 값으로 덮어쓰지 않음).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.lastSeenReleaseVersion = :next where u.id = :id and "
            + "((:old is null and u.lastSeenReleaseVersion is null) or u.lastSeenReleaseVersion = :old)")
    int updateLastSeenReleaseVersion(@Param("id") Long id, @Param("old") String old, @Param("next") String next);

    /** 마지막 확인 버전만 읽는다. */
    @Query("select u.lastSeenReleaseVersion from User u where u.id = :id")
    Optional<String> findLastSeenReleaseVersion(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
