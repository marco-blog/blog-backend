package net.java21.blog.backend.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.admin.SuperAdminGuard;
import net.java21.blog.backend.admin.audit.AdminAuditLog;
import net.java21.blog.backend.admin.audit.AdminAuditLogRepository;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.auth.domain.RefreshToken;
import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.security.SuspendedUserRegistry;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 005 T038: 회원 정지·해제. ACTIVE만 정지(이미 정지면 그대로, 탈퇴 409), 자기 자신 422, 관리자 정지는 SUPER_ADMIN만(403), 마지막 활성
 * SUPER_ADMIN 409, 갱신 토큰 전부 폐기, 상태가 바뀐 경우에만 작업 기록, 정지 목록은 커밋 뒤에 바뀐다.
 */
@JpaRepositoryTest
class SuspensionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final String IP = "203.0.113.9";

    @Autowired
    private EntityManager em;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private AdminAuditLogRepository auditLogRepository;

    private SuspendedUserRegistry registry;
    private SuspensionService service;
    private JpaFixtures fx;
    private User admin;
    private User superAdmin;
    private User member;

    @BeforeEach
    void setUp() {
        registry = new SuspendedUserRegistry(new AuthProperties(Duration.ofMinutes(30), Duration.ofHours(4),
                Duration.ofDays(7), Duration.ofSeconds(10), "unit-test-only-jwt-secret-0123456789abcdef", 5,
                Duration.ofMinutes(10)));
        service = new SuspensionService(userRepository, refreshTokenRepository, new SuperAdminGuard(adminUserRepository),
                registry, new AdminAuditService(auditLogRepository, userRepository), Clock.fixed(NOW, ZoneOffset.UTC));
        fx = new JpaFixtures(em);
        admin = role(fx.user("admin"), UserRole.ADMIN);
        superAdmin = role(fx.user("root"), UserRole.SUPER_ADMIN);
        member = fx.user("member");
        em.persist(new RefreshToken(member, "family-1", "1".repeat(64), NOW.plusSeconds(3600),
                NOW.plusSeconds(7200)));
        em.persist(new RefreshToken(member, "family-2", "2".repeat(64), NOW.plusSeconds(3600),
                NOW.plusSeconds(7200)));
        fx.flushAndClear();
    }

    @Test
    void suspendRevokesTokensAuditsAndRegistersAfterCommit() {
        assertThat(service.suspend(admin.getId(), member.getId(), "  스팸 반복  ", IP)).isTrue();
        assertThat(registry.contains(member.getId())).isFalse();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(registry.contains(member.getId())).isTrue();
        fx.flushAndClear();

        assertThat(em.find(User.class, member.getId()).getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(em.createQuery("select count(t) from RefreshToken t where t.user.id = :id and t.revokedAt = :now",
                Long.class).setParameter("id", member.getId()).setParameter("now", NOW).getSingleResult())
                .isEqualTo(2);
        AdminAuditLog log = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("USER", member.getId())
                .getFirst();
        assertThat(log.getAction()).isEqualTo("USER_SUSPEND");
        assertThat(log.getBefore()).isEqualTo(Map.of("status", "ACTIVE"));
        assertThat(log.getAfter()).isEqualTo(Map.of("status", "SUSPENDED"));
        assertThat(log.getReason()).isEqualTo("스팸 반복");
        assertThat(log.getRequestIp()).isEqualTo(IP);

        assertThat(service.suspend(admin.getId(), member.getId(), "다시", IP)).isFalse();
        assertThat(auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("USER", member.getId())).hasSize(1);
    }

    @Test
    void unsuspendRestoresAndIsIdempotent() {
        TestEntities.with(em.find(User.class, member.getId()), "status", UserStatus.SUSPENDED);
        registry.add(member.getId());
        fx.flushAndClear();

        assertThat(service.unsuspend(admin.getId(), member.getId(), null, IP)).isTrue();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(registry.contains(member.getId())).isFalse();
        fx.flushAndClear();
        assertThat(em.find(User.class, member.getId()).getStatus()).isEqualTo(UserStatus.ACTIVE);
        AdminAuditLog log = auditLogRepository.findByTargetTypeAndTargetIdOrderByIdDesc("USER", member.getId())
                .getFirst();
        assertThat(log.getAction()).isEqualTo("USER_UNSUSPEND");
        assertThat(log.getReason()).isNull();

        assertThat(service.unsuspend(admin.getId(), member.getId(), "", IP)).isFalse();
        assertError(() -> service.unsuspend(admin.getId(), 999_999L, null, IP), ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void rejectsSelfWithdrawnUnknownAndBadReasons() {
        assertError(() -> service.suspend(admin.getId(), admin.getId(), "자신", IP), ErrorCode.CANNOT_SUSPEND_SELF);
        assertError(() -> service.suspend(admin.getId(), 999_999L, "없음", IP), ErrorCode.USER_NOT_FOUND);
        User gone = fx.user("gone");
        gone.withdraw(NOW);
        fx.flushAndClear();
        assertError(() -> service.suspend(admin.getId(), gone.getId(), "탈퇴", IP), ErrorCode.USER_NOT_ACTIVE);
        assertThatThrownBy(() -> service.suspend(admin.getId(), member.getId(), "   ", IP))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors()).singleElement()
                        .satisfies(f -> assertThat(f.code()).isEqualTo("REQUIRED")));
        assertThatThrownBy(() -> service.suspend(admin.getId(), member.getId(), "가".repeat(501), IP))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.fieldErrors()).singleElement()
                        .satisfies(f -> assertThat(f.code()).isEqualTo("TOO_LONG")));
        assertThat(SuspensionService.reason("가".repeat(500), true, "reason")).hasSize(500);
    }

    @Test
    void onlySuperAdminsSuspendAdminsAndTheLastSuperAdminStays() {
        User other = role(fx.user("other-admin"), UserRole.ADMIN);
        fx.flushAndClear();

        assertError(() -> service.suspend(admin.getId(), other.getId(), "관리자", IP), ErrorCode.FORBIDDEN);
        assertError(() -> service.suspend(admin.getId(), superAdmin.getId(), "최고 관리자", IP), ErrorCode.FORBIDDEN);
        assertThat(service.suspend(superAdmin.getId(), other.getId(), "관리자", IP)).isTrue();

        User second = role(fx.user("root2"), UserRole.SUPER_ADMIN);
        fx.flushAndClear();
        assertThat(service.suspend(superAdmin.getId(), second.getId(), "둘째", IP)).isTrue();
        fx.flushAndClear();
        assertError(() -> service.suspend(second.getId(), superAdmin.getId(), "정지된 회원의 요청", IP),
                ErrorCode.FORBIDDEN);
        // superAdmin만 활성으로 남았다. 다른 활성 SUPER_ADMIN을 하나 더 만들어 superAdmin 자신을 정지하게 해 본다.
        User third = role(fx.user("root3"), UserRole.SUPER_ADMIN);
        fx.flushAndClear();
        assertThat(service.suspend(third.getId(), superAdmin.getId(), "교대", IP)).isTrue();
        fx.flushAndClear();
        // 이제 활성 SUPER_ADMIN은 third 하나: 잠금 안에서 다른 활성 SUPER_ADMIN이 없으면 409.
        User fourthAdmin = role(fx.user("root4"), UserRole.SUPER_ADMIN);
        TestEntities.with(fourthAdmin, "status", UserStatus.SUSPENDED);
        fx.flushAndClear();
        SuperAdminGuard guard = new SuperAdminGuard(adminUserRepository);
        assertThat(guard.lockActiveSuperAdminIds()).containsExactly(third.getId());
        assertError(() -> guard.ensureAnotherActiveSuperAdmin(third.getId()), ErrorCode.LAST_SUPER_ADMIN);
        guard.ensureAnotherActiveSuperAdmin(fourthAdmin.getId());
        guard.requireSuperAdmin(third.getId());
        assertError(() -> guard.requireSuperAdmin(fourthAdmin.getId()), ErrorCode.FORBIDDEN);
        assertError(() -> guard.requireSuperAdmin(999_999L), ErrorCode.FORBIDDEN);
    }

    private User role(User user, UserRole role) {
        return TestEntities.with(user, "role", role);
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
