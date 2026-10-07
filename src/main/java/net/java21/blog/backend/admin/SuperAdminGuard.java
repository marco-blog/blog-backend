package net.java21.blog.backend.admin;

import java.util.List;

import net.java21.blog.backend.admin.user.AdminUserRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 최고 관리자 규칙(006 FR-105, 005 research M5) 한 곳. 005 회원 정지와 006 권한 변경이 함께 쓴다.
 * <ul>
 *   <li>{@link #requireSuperAdmin}: 요청한 관리자가 지금(DB 기준) 활성 SUPER_ADMIN이 아니면 403 {@code FORBIDDEN}.</li>
 *   <li>{@link #lockActiveSuperAdminIds}: 활성 SUPER_ADMIN 행을 잠그고 id를 돌려준다.</li>
 *   <li>{@link #ensureAnotherActiveSuperAdmin}: 대상 말고 활성 SUPER_ADMIN이 없으면 409 {@code LAST_SUPER_ADMIN}.</li>
 * </ul>
 */
@Component
public class SuperAdminGuard {

    private final AdminUserRepository adminUserRepository;

    public SuperAdminGuard(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    @Transactional(readOnly = true)
    public void requireSuperAdmin(long userId) {
        boolean superAdmin = adminUserRepository.findRoleAndStatusById(userId)
                .filter(r -> r.getRole() == UserRole.SUPER_ADMIN && r.getStatus() == UserStatus.ACTIVE)
                .isPresent();
        if (!superAdmin) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Super admin required");
        }
    }

    /** 활성 SUPER_ADMIN 행을 잠그고(트랜잭션 끝까지) id를 id 순으로 돌려준다. 호출하는 쪽 트랜잭션이 있어야 한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> lockActiveSuperAdminIds() {
        return adminUserRepository.findIdsByRoleAndStatus(UserRole.SUPER_ADMIN, UserStatus.ACTIVE);
    }

    /** {@code targetId}를 정지·강등해도 다른 활성 SUPER_ADMIN이 남는지(잠금 안에서 확인). 아니면 409 {@code LAST_SUPER_ADMIN}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureAnotherActiveSuperAdmin(long targetId) {
        boolean another = lockActiveSuperAdminIds().stream().anyMatch(id -> id != targetId);
        if (!another) {
            throw new BusinessException(ErrorCode.LAST_SUPER_ADMIN, "Cannot remove the last super admin: " + targetId);
        }
    }
}
