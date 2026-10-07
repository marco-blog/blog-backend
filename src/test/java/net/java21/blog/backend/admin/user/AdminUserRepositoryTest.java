package net.java21.blog.backend.admin.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 006 T044: 관리자 목록은 ADMIN·SUPER_ADMIN 전원(정지 포함), 권한 높은 순 → 닉네임 → id, 쿼리 1회. */
@JpaRepositoryTest
class AdminUserRepositoryTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private AdminUserRepository repository;
    @Autowired
    private QueryCounter queryCounter;

    @Test
    void listsAdministratorsSuperAdminsFirstThenByNickname() {
        JpaFixtures fx = new JpaFixtures(em);
        User zeta = role(fx.user("zeta"), UserRole.SUPER_ADMIN);
        User alpha = role(fx.user("alpha"), UserRole.ADMIN);
        User beta = role(fx.user("beta"), UserRole.SUPER_ADMIN);
        User suspended = role(fx.user("gamma"), UserRole.ADMIN);
        TestEntities.with(suspended, "status", UserStatus.SUSPENDED);
        fx.user("member");
        fx.flushAndClear();

        queryCounter.reset();
        List<User> admins = repository.findByRoleInOrdered(List.of(UserRole.ADMIN, UserRole.SUPER_ADMIN),
                UserRole.SUPER_ADMIN);

        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(admins).extracting(User::getId)
                .containsExactly(beta.getId(), zeta.getId(), alpha.getId(), suspended.getId());
        assertThat(repository.findUserById(alpha.getId())).get().extracting(User::getNickname).isEqualTo("alpha");
        assertThat(repository.findUserById(-1L)).isEmpty();
    }

    private static User role(User user, UserRole role) {
        TestEntities.with(user, "role", role);
        return user;
    }
}
