package net.java21.blog.backend.admin;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 006 T007(data-model "001 테이블 변경", research A9): 최고 관리자 잠금·확인은 DB의 현재 role·status만 본다(세션·토큰 값 아님).
 */
@JpaRepositoryTest
@Import(SuperAdminGuard.class)
class SuperAdminGuardTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private SuperAdminGuard guard;
    @Autowired
    private PersonalDataHasher hasher;

    private User root;
    private User admin;
    private User member;
    private User suspendedRoot;
    private User withdrawnRoot;

    @BeforeEach
    void setUp() {
        root = persist("root@example.com", UserRole.SUPER_ADMIN, UserStatus.ACTIVE);
        admin = persist("admin@example.com", UserRole.ADMIN, UserStatus.ACTIVE);
        member = persist("member@example.com", UserRole.USER, UserStatus.ACTIVE);
        suspendedRoot = persist("sus@example.com", UserRole.SUPER_ADMIN, UserStatus.SUSPENDED);
        withdrawnRoot = persist("gone@example.com", UserRole.SUPER_ADMIN, UserStatus.WITHDRAWN);
        em.flush();
        em.clear();
    }

    @Test
    void lockReturnsOnlyActiveSuperAdminsInOneQuery() {
        queryCounter.reset();
        assertThat(guard.lockActiveSuperAdminIds()).containsExactly(root.getId());
        assertThat(queryCounter.count()).isEqualTo(1);
    }

    @Test
    void requireSuperAdminReadsCurrentRoleFromDatabase() {
        guard.requireSuperAdmin(root.getId());

        assertCode(() -> guard.requireSuperAdmin(admin.getId()), ErrorCode.FORBIDDEN);
        assertCode(() -> guard.requireSuperAdmin(member.getId()), ErrorCode.FORBIDDEN);
        assertCode(() -> guard.requireSuperAdmin(suspendedRoot.getId()), ErrorCode.FORBIDDEN);
        assertCode(() -> guard.requireSuperAdmin(withdrawnRoot.getId()), ErrorCode.FORBIDDEN);
        assertCode(() -> guard.requireSuperAdmin(-1L), ErrorCode.FORBIDDEN);
    }

    @Test
    void ensureAnotherActiveSuperAdminRejectsTheLastOne() {
        assertCode(() -> guard.ensureAnotherActiveSuperAdmin(root.getId()), ErrorCode.LAST_SUPER_ADMIN);
        // 대상이 SUPER_ADMIN이 아니면 root가 남는다
        guard.ensureAnotherActiveSuperAdmin(admin.getId());

        User second = persist("second@example.com", UserRole.SUPER_ADMIN, UserStatus.ACTIVE);
        em.flush();
        guard.ensureAnotherActiveSuperAdmin(root.getId());
        guard.ensureAnotherActiveSuperAdmin(second.getId());
    }

    private User persist(String email, UserRole role, UserStatus status) {
        User u = new User(PersonalDataHasher.normalizeEmail(email), hasher.hashEmail(email), "$2a$hash", "닉",
                null, null, "2026-10-06", T0);
        ReflectionTestUtils.setField(u, "role", role);
        ReflectionTestUtils.setField(u, "status", status);
        em.persist(u);
        return u;
    }
}
