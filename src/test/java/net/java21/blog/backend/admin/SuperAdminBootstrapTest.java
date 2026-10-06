package net.java21.blog.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import net.java21.blog.backend.admin.user.AdminUserRepository;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * 첫 최고 관리자 지정(T154, 006 FR-105): 기동 시 SUPER_ADMIN이 없고 {@code blog.admin.bootstrap-super-admin-email}이 있으면 그 이메일
 * (해시로 조회)의 회원을 SUPER_ADMIN으로, 이미 있으면 아무것도 하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class SuperAdminBootstrapTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Mock
    private AdminUserRepository adminUserRepository;
    @Mock
    private UserRepository userRepository;

    @Test
    void promotesTheConfiguredMemberWhenNoSuperAdminExists() {
        when(adminUserRepository.existsByRole(UserRole.SUPER_ADMIN)).thenReturn(false);
        when(userRepository.findByEmailHash(TestEntities.HASHER.hashEmail("marco@example.com")))
                .thenReturn(Optional.of(TestEntities.user(7L, "marco@example.com", "{hash}", "마르코")));

        bootstrap(" Marco@Example.com ").run(new DefaultApplicationArguments());

        verify(adminUserRepository).updateRole(7L, UserRole.SUPER_ADMIN, NOW);
    }

    @Test
    void doesNothingWhenASuperAdminAlreadyExists() {
        when(adminUserRepository.existsByRole(UserRole.SUPER_ADMIN)).thenReturn(true);

        assertThat(bootstrap("marco@example.com").bootstrap()).isFalse();

        verifyNoInteractions(userRepository);
        verify(adminUserRepository, never()).updateRole(anyLong(), any(), any());
    }

    @Test
    void doesNothingWithoutTheProperty() {
        assertThat(bootstrap(null).bootstrap()).isFalse();
        assertThat(bootstrap("  ").bootstrap()).isFalse();

        verifyNoInteractions(adminUserRepository, userRepository);
    }

    @Test
    void unknownOrInactiveMemberIsNotPromoted() {
        when(adminUserRepository.existsByRole(UserRole.SUPER_ADMIN)).thenReturn(false);
        when(userRepository.findByEmailHash(anyString())).thenReturn(Optional.empty(),
                Optional.of(TestEntities.with(TestEntities.user(8L), "status", UserStatus.WITHDRAWN)));

        assertThat(bootstrap("nobody@example.com").bootstrap()).isFalse();
        assertThat(bootstrap("user8@example.com").bootstrap()).isFalse();

        verify(adminUserRepository, never()).updateRole(anyLong(), any(), any());
    }

    private SuperAdminBootstrap bootstrap(String email) {
        return new SuperAdminBootstrap(new AdminProperties(email), adminUserRepository, userRepository,
                TestEntities.HASHER, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
