package net.java21.blog.backend.admin.user;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.SuperAdminGuard;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.admin.user.dto.AdminMemberResponse;
import net.java21.blog.backend.admin.user.dto.RoleChangeRequest;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 권한(006 FR-105): 관리자 목록과 최고 관리자의 권한 변경.
 * <p>권한 변경은 모두 활성 SUPER_ADMIN 행 잠금({@link SuperAdminGuard#lockActiveSuperAdminIds})을 먼저 잡아 직렬화한다. 그래서
 * 두 최고 관리자가 서로를 동시에 낮춰도 한쪽만 성공하고 활성 최고 관리자가 남는다. 요청자 확인도 그 잠금 안에서 한다(잠금 목록에
 * 없으면 이미 강등·정지된 것이므로 403).
 */
@Service
public class AdminRoleService {

    static final String FIELD_ROLE = "role";

    private final AdminUserRepository adminUserRepository;
    private final SuperAdminGuard superAdminGuard;
    private final AdminAuditService auditService;
    private final Clock clock;

    public AdminRoleService(AdminUserRepository adminUserRepository, SuperAdminGuard superAdminGuard,
            AdminAuditService auditService, Clock clock) {
        this.adminUserRepository = adminUserRepository;
        this.superAdminGuard = superAdminGuard;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminMemberResponse> admins() {
        return adminUserRepository
                .findByRoleInOrdered(List.of(UserRole.ADMIN, UserRole.SUPER_ADMIN), UserRole.SUPER_ADMIN).stream()
                .map(AdminRoleService::toResponse)
                .toList();
    }

    /**
     * 회원 권한을 바꾼다. 순서: 값 확인(400) → 자기 자신(422) → 잠금 → 대상(404) → 마지막 최고 관리자(409) → 요청자(403) →
     * 같은 값(200, 기록 없음) → 비활성 회원에게 관리자 이상(409) → 변경과 작업 기록. 마지막 최고 관리자 확인을 요청자 확인보다 먼저
     * 해서, 두 최고 관리자가 서로를 동시에 낮추면 잠금을 늦게 얻은 쪽이 409 {@code LAST_SUPER_ADMIN}을 받는다(research A9).
     */
    @Transactional
    public AdminMemberResponse changeRole(long requesterId, long targetId, RoleChangeRequest request,
            String requestIp) {
        UserRole role = parseRole(request);
        if (requesterId == targetId) {
            throw new BusinessException(ErrorCode.CANNOT_CHANGE_OWN_ROLE, "Cannot change own role");
        }
        List<Long> activeSuperAdmins = superAdminGuard.lockActiveSuperAdminIds();
        User target = adminUserRepository.findUserById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found: " + targetId));
        UserRole before = target.getRole();
        if (before == UserRole.SUPER_ADMIN && role != UserRole.SUPER_ADMIN
                && activeSuperAdmins.stream().noneMatch(id -> id != targetId)) {
            throw new BusinessException(ErrorCode.LAST_SUPER_ADMIN, "Cannot demote the last super admin: " + targetId);
        }
        if (!activeSuperAdmins.contains(requesterId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Super admin required");
        }
        if (before == role) {
            return toResponse(target);
        }
        if (role != UserRole.USER && target.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.USER_NOT_ACTIVE, "User is not active: " + targetId);
        }
        adminUserRepository.updateRole(targetId, role, clock.instant());
        String action = role.ordinal() > before.ordinal() ? AuditActions.ROLE_GRANT : AuditActions.ROLE_REVOKE;
        auditService.record(requesterId, action, AuditActions.TARGET_USER, targetId,
                Map.of(FIELD_ROLE, before.name()), Map.of(FIELD_ROLE, role.name()), requestIp);
        return new AdminMemberResponse(targetId, target.getNickname(), role, target.getStatus(),
                target.getCreatedAt());
    }

    private static UserRole parseRole(RoleChangeRequest request) {
        String value = request == null ? null : request.role();
        if (value == null || value.isBlank()) {
            throw invalid("REQUIRED", Map.of());
        }
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw invalid("INVALID", Map.of("allowed", List.of(UserRole.values()).stream().map(Enum::name).toList()));
        }
    }

    private static BusinessException invalid(String code, Map<String, Object> params) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "role is " + code,
                List.of(new FieldError(FIELD_ROLE, code, params)));
    }

    private static AdminMemberResponse toResponse(User user) {
        return new AdminMemberResponse(user.getId(), user.getNickname(), user.getRole(), user.getStatus(),
                user.getCreatedAt());
    }
}
