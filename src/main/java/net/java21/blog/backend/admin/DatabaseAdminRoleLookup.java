package net.java21.blog.backend.admin;

import java.util.EnumSet;
import java.util.Set;

import net.java21.blog.backend.admin.user.AdminUserRepository;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import org.springframework.stereotype.Component;

/** {@link AdminRoleLookup}: {@code users.role}·{@code users.status}를 요청마다 쿼리 1회로 확인한다. */
@Component
public class DatabaseAdminRoleLookup implements AdminRoleLookup {

    static final Set<UserRole> ADMIN_ROLES = EnumSet.of(UserRole.ADMIN, UserRole.SUPER_ADMIN);

    private final AdminUserRepository adminUserRepository;

    public DatabaseAdminRoleLookup(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    @Override
    public boolean isActiveAdmin(long userId) {
        return adminUserRepository.existsByIdAndStatusAndRoleIn(userId, UserStatus.ACTIVE, ADMIN_ROLES);
    }
}
