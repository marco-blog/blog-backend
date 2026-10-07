package net.java21.blog.backend.admin.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.admin.SuperAdminGuard;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.security.AuthProperties;
import net.java21.blog.backend.security.SuspendedUserRegistry;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 005 T038: 정지 목록. 트랜잭션 밖에서 부르면 바로 바뀌고, 항목은 접근 토큰 수명(30분)이 지나면 사라진다.
 */
@ExtendWith(MockitoExtension.class)
class SuspensionRegistryTest {

    private static final AuthProperties AUTH = new AuthProperties(Duration.ofMinutes(30), Duration.ofHours(4),
            Duration.ofDays(7), Duration.ofSeconds(10), "unit-test-only-jwt-secret-0123456789abcdef", 5,
            Duration.ofMinutes(10));

    @Mock
    private UserRepository userRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private SuperAdminGuard guard;
    @Mock
    private AdminAuditService auditService;

    @Test
    void withoutATransactionTheRegistryChangesImmediately() {
        SuspendedUserRegistry registry = new SuspendedUserRegistry(AUTH);
        SuspensionService service = new SuspensionService(userRepository, refreshTokenRepository, guard, registry,
                auditService, Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));
        User member = TestEntities.user(7L);
        when(userRepository.findById(7L)).thenReturn(Optional.of(member));
        when(userRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(member));

        assertThat(service.suspend(1L, 7L, "스팸", "::1")).isTrue();
        assertThat(registry.contains(7L)).isTrue();
        assertThat(service.unsuspend(1L, 7L, null, "::1")).isTrue();
        assertThat(registry.contains(7L)).isFalse();
    }

    @Test
    void entriesExpireWithTheAccessTokenLifetime() {
        long[] nanos = {0};
        Ticker ticker = () -> nanos[0];
        SuspendedUserRegistry registry = new SuspendedUserRegistry(AUTH, ticker);
        registry.add(7L);
        assertThat(registry.contains(7L)).isTrue();
        assertThat(registry.contains(8L)).isFalse();
        nanos[0] = Duration.ofMinutes(29).toNanos();
        assertThat(registry.contains(7L)).isTrue();
        nanos[0] = Duration.ofMinutes(31).toNanos();
        assertThat(registry.contains(7L)).isFalse();
        registry.add(8L);
        registry.remove(8L);
        assertThat(registry.contains(8L)).isFalse();
    }
}
