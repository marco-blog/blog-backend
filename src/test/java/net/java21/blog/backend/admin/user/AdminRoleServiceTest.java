package net.java21.blog.backend.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.admin.SuperAdminGuard;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.user.dto.AdminMemberResponse;
import net.java21.blog.backend.admin.user.dto.RoleChangeRequest;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 006 T043(FR-105): 권한 변경 순서와 오류(400·422·403·404·409 두 가지), 같은 값은 기록 없이 200, 높아지면 ROLE_GRANT·낮아지면
 * ROLE_REVOKE(before/after {@code role}), 잠금을 먼저 잡는다.
 */
class AdminRoleServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final long ROOT = 1L;
    private static final long OTHER_ROOT = 2L;
    private static final long ADMIN = 3L;
    private static final long MEMBER = 4L;
    private static final long SUSPENDED = 5L;

    private final AdminUserRepository users = mock(AdminUserRepository.class);
    private final SuperAdminGuard guard = mock(SuperAdminGuard.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminRoleService service = new AdminRoleService(users, guard, audit, new MutableClock(NOW));

    @BeforeEach
    void setUp() {
        when(guard.lockActiveSuperAdminIds()).thenReturn(List.of(ROOT, OTHER_ROOT));
        stub(ROOT, UserRole.SUPER_ADMIN, UserStatus.ACTIVE);
        stub(OTHER_ROOT, UserRole.SUPER_ADMIN, UserStatus.ACTIVE);
        stub(ADMIN, UserRole.ADMIN, UserStatus.ACTIVE);
        stub(MEMBER, UserRole.USER, UserStatus.ACTIVE);
        stub(SUSPENDED, UserRole.USER, UserStatus.SUSPENDED);
    }

    @Test
    void grantAndRevokeAreAudited() {
        AdminMemberResponse granted = service.changeRole(ROOT, MEMBER, new RoleChangeRequest("ADMIN"), "10.0.0.1");
        assertThat(granted.role()).isEqualTo(UserRole.ADMIN);
        assertThat(granted.nickname()).isEqualTo("user" + MEMBER);
        verify(users).updateRole(MEMBER, UserRole.ADMIN, NOW);
        verify(audit).record(ROOT, AuditActions.ROLE_GRANT, AuditActions.TARGET_USER, MEMBER, Map.of("role", "USER"),
                Map.of("role", "ADMIN"), "10.0.0.1");

        service.changeRole(ROOT, OTHER_ROOT, new RoleChangeRequest("ADMIN"), "10.0.0.1");
        verify(audit).record(ROOT, AuditActions.ROLE_REVOKE, AuditActions.TARGET_USER, OTHER_ROOT,
                Map.of("role", "SUPER_ADMIN"), Map.of("role", "ADMIN"), "10.0.0.1");

        service.changeRole(ROOT, ADMIN, new RoleChangeRequest("USER"), null);
        verify(audit).record(ROOT, AuditActions.ROLE_REVOKE, AuditActions.TARGET_USER, ADMIN, Map.of("role", "ADMIN"),
                Map.of("role", "USER"), null);
    }

    @Test
    void sameRoleIsNoOp() {
        AdminMemberResponse same = service.changeRole(ROOT, ADMIN, new RoleChangeRequest("ADMIN"), "ip");
        assertThat(same.role()).isEqualTo(UserRole.ADMIN);
        verify(users, never()).updateRole(anyLong(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void validationAndOwnRoleComeBeforeLocking() {
        assertField(new RoleChangeRequest(null), "REQUIRED");
        assertField(new RoleChangeRequest(" "), "REQUIRED");
        assertField(null, "REQUIRED");
        assertField(new RoleChangeRequest("OWNER"), "INVALID");
        assertCode(() -> service.changeRole(ROOT, ROOT, new RoleChangeRequest("USER"), "ip"),
                ErrorCode.CANNOT_CHANGE_OWN_ROLE);
        verifyNoInteractions(guard);
    }

    @Test
    void requesterMustBeLockedActiveSuperAdmin() {
        assertCode(() -> service.changeRole(ADMIN, MEMBER, new RoleChangeRequest("ADMIN"), "ip"), ErrorCode.FORBIDDEN);
        verify(guard).lockActiveSuperAdminIds();
        verify(users, never()).updateRole(anyLong(), any(), any());
    }

    @Test
    void missingTarget() {
        when(users.findUserById(99L)).thenReturn(Optional.empty());
        assertCode(() -> service.changeRole(ROOT, 99L, new RoleChangeRequest("ADMIN"), "ip"), ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void lastSuperAdminCannotBeDemoted() {
        // 동시에 서로를 낮출 때 늦게 잠금을 얻은 쪽: 요청자는 이미 강등돼 잠금 목록에 대상만 남았다 → 403이 아니라 409
        when(guard.lockActiveSuperAdminIds()).thenReturn(List.of(OTHER_ROOT));
        assertCode(() -> service.changeRole(ROOT, OTHER_ROOT, new RoleChangeRequest("USER"), "ip"),
                ErrorCode.LAST_SUPER_ADMIN);
        assertCode(() -> service.changeRole(ROOT, OTHER_ROOT, new RoleChangeRequest("ADMIN"), "ip"),
                ErrorCode.LAST_SUPER_ADMIN);
        verify(users, never()).updateRole(anyLong(), any(), any());
    }

    @Test
    void suspendedSuperAdminCanBeDemotedWhileAnotherRemains() {
        when(guard.lockActiveSuperAdminIds()).thenReturn(List.of(ROOT));
        stub(6L, UserRole.SUPER_ADMIN, UserStatus.SUSPENDED);
        service.changeRole(ROOT, 6L, new RoleChangeRequest("USER"), "ip");
        verify(users).updateRole(6L, UserRole.USER, NOW);
        stub(8L, UserRole.ADMIN, UserStatus.SUSPENDED);
        assertCode(() -> service.changeRole(ROOT, 8L, new RoleChangeRequest("SUPER_ADMIN"), "ip"),
                ErrorCode.USER_NOT_ACTIVE);
    }

    @Test
    void grantTable() {
        service.changeRole(ROOT, MEMBER, new RoleChangeRequest("SUPER_ADMIN"), "ip");
        service.changeRole(ROOT, ADMIN, new RoleChangeRequest("SUPER_ADMIN"), "ip");
        verify(audit).record(ROOT, AuditActions.ROLE_GRANT, AuditActions.TARGET_USER, MEMBER, Map.of("role", "USER"),
                Map.of("role", "SUPER_ADMIN"), "ip");
        verify(audit).record(ROOT, AuditActions.ROLE_GRANT, AuditActions.TARGET_USER, ADMIN, Map.of("role", "ADMIN"),
                Map.of("role", "SUPER_ADMIN"), "ip");
        service.changeRole(ROOT, OTHER_ROOT, new RoleChangeRequest("USER"), "ip");
        verify(audit).record(ROOT, AuditActions.ROLE_REVOKE, AuditActions.TARGET_USER, OTHER_ROOT,
                Map.of("role", "SUPER_ADMIN"), Map.of("role", "USER"), "ip");
    }

    @Test
    void inactiveUserCannotBecomeAdmin() {
        assertCode(() -> service.changeRole(ROOT, SUSPENDED, new RoleChangeRequest("ADMIN"), "ip"),
                ErrorCode.USER_NOT_ACTIVE);
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyLong(), any(), any(), any());
        stub(7L, UserRole.ADMIN, UserStatus.WITHDRAWN);
        service.changeRole(ROOT, 7L, new RoleChangeRequest("USER"), "ip");
        verify(users).updateRole(7L, UserRole.USER, NOW);
    }

    @Test
    void adminListOrdersSuperAdminsFirst() {
        User root = users.findUserById(ROOT).orElseThrow();
        User admin = users.findUserById(ADMIN).orElseThrow();
        when(users.findByRoleInOrdered(List.of(UserRole.ADMIN, UserRole.SUPER_ADMIN), UserRole.SUPER_ADMIN))
                .thenReturn(List.of(root, admin));
        assertThat(service.admins()).extracting(AdminMemberResponse::userId).containsExactly(ROOT, ADMIN);
        assertThat(service.admins().get(0).status()).isEqualTo(UserStatus.ACTIVE);
    }

    private User stub(long id, UserRole role, UserStatus status) {
        User user = TestEntities.user(id);
        TestEntities.with(user, "role", role);
        TestEntities.with(user, "status", status);
        TestEntities.with(user, "nickname", "user" + id);
        when(users.findUserById(id)).thenReturn(Optional.of(user));
        return user;
    }

    private void assertField(RoleChangeRequest request, String code) {
        BusinessException e = catchThrowableOfType(BusinessException.class,
                () -> service.changeRole(ROOT, MEMBER, request, "ip"));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(e.fieldErrors()).singleElement().satisfies(f -> {
            assertThat(f.field()).isEqualTo("role");
            assertThat(f.code()).isEqualTo(code);
        });
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        net.java21.blog.backend.support.BusinessAssertions.assertCode(call, code);
    }
}
