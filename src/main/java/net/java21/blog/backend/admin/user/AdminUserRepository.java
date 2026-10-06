package net.java21.blog.backend.admin.user;

import java.time.Instant;
import java.util.Collection;

import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
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
}
