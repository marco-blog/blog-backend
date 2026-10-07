package net.java21.blog.backend.admin.user;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 관리자 기능이 쓰는 회원 조회·변경(권한 확인, 블로그 한도, 첫 최고 관리자). 일반 회원 기능의 {@code UserRepository}와 나눠 둔다.
 * 변경은 대상 회원 행 하나만 바꾸는 UPDATE이며, 영속성 컨텍스트를 비워 이후 조회가 새 값을 읽게 한다.
 */
public interface AdminUserRepository extends Repository<User, Long> {

    boolean existsByIdAndStatusAndRoleIn(Long id, UserStatus status, Collection<UserRole> roles);

    boolean existsByRole(UserRole role);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.maxBlogs = :maxBlogs, u.updatedAt = :now where u.id = :id")
    int updateMaxBlogs(@Param("id") Long id, @Param("maxBlogs") Integer maxBlogs, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.role = :role, u.updatedAt = :now where u.id = :id")
    int updateRole(@Param("id") Long id, @Param("role") UserRole role, @Param("now") Instant now);

    /** 권한·상태(005·006 관리자 정지·권한 변경의 판단). */
    interface RoleAndStatus {
        UserRole getRole();

        UserStatus getStatus();
    }

    @Query("select u.role as role, u.status as status from User u where u.id = :id")
    Optional<RoleAndStatus> findRoleAndStatusById(@Param("id") Long id);

    /**
     * 이 권한·상태인 회원 id를 행 잠금으로 읽는다({@code SELECT ... FOR UPDATE}). 마지막 최고 관리자 확인(005 정지, 006 권한 변경)이
     * 동시에 실행돼도 둘 다 "다른 최고 관리자가 있다"고 보지 않게 한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.role = :role and u.status = :status order by u.id")
    List<User> lockByRoleAndStatus(@Param("role") UserRole role, @Param("status") UserStatus status);

    /** {@link #lockByRoleAndStatus}의 id만(행 잠금은 같다). */
    default List<Long> findIdsByRoleAndStatus(UserRole role, UserStatus status) {
        return lockByRoleAndStatus(role, status).stream().map(User::getId).toList();
    }
}
