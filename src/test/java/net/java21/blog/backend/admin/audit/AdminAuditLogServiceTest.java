package net.java21.blog.backend.admin.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.admin.audit.AdminAuditLogQueryRepository.Criteria;
import net.java21.blog.backend.admin.audit.AdminAuditLogService.Query;
import net.java21.blog.backend.admin.audit.dto.AuditLogDetailResponse;
import net.java21.blog.backend.admin.user.AdminUserRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/** 006 T041(FR-106, research A6): 기간 해석(관리자 시간대, 기본 7일, 최대 366일), 작업 쉼표 목록, 상세의 요청 IP는 최고 관리자만. */
class AdminAuditLogServiceTest {

    /** 서울 10월 8일 00:30. */
    private static final Instant NOW = Instant.parse("2026-10-07T15:30:00Z");
    private static final PageRequest PAGE = PageRequest.of(0, 20);

    private final AdminAuditLogQueryRepository repository = mock(AdminAuditLogQueryRepository.class);
    private final AdminUserRepository users = mock(AdminUserRepository.class);
    private final AdminAuditLogService service = new AdminAuditLogService(repository, users, new MutableClock(NOW));

    @BeforeEach
    void setUp() {
        when(users.findTimeZoneById(1L)).thenReturn(Optional.of("Asia/Seoul"));
        when(users.findTimeZoneById(2L)).thenReturn(Optional.of("Bad/Zone"));
        when(repository.search(any(), any())).thenReturn(Page.empty());
    }

    @Test
    void defaultPeriodIsLastSevenDaysInAdminZone() {
        Criteria c = search(1L, new Query(null, null, null, null, null, null, null));
        assertThat(c.from()).isEqualTo(Instant.parse("2026-10-01T15:00:00Z"));   // 서울 10-02 0시
        assertThat(c.to()).isEqualTo(Instant.parse("2026-10-08T15:00:00Z"));     // 서울 10-09 0시
        assertThat(c.actions()).isEmpty();
    }

    @Test
    void explicitPeriodAndFilters() {
        Criteria c = search(1L, new Query("2026-01-01", "2026-01-31", 9L, " role_grant, ROLE_REVOKE ,,ROLE_GRANT",
                " USER ", 7L, " "));
        assertThat(c.from()).isEqualTo(Instant.parse("2025-12-31T15:00:00Z"));
        assertThat(c.to()).isEqualTo(Instant.parse("2026-01-31T15:00:00Z"));
        assertThat(c.adminId()).isEqualTo(9L);
        assertThat(c.actions()).containsExactly("ROLE_GRANT", "ROLE_REVOKE");
        assertThat(c.targetType()).isEqualTo("USER");
        assertThat(c.targetId()).isEqualTo(7L);
        assertThat(c.targetKey()).isNull();

        Criteria fromOnly = search(1L, new Query("2026-10-05", null, null, null, null, null, "k"));
        assertThat(fromOnly.to()).as("오늘을 넘지 않음").isEqualTo(Instant.parse("2026-10-08T15:00:00Z"));
        Criteria fromOld = search(1L, new Query("2026-01-01", null, null, null, null, null, null));
        assertThat(fromOld.to()).isEqualTo(Instant.parse("2026-01-07T15:00:00Z"));
        Criteria toOnly = search(2L, new Query(null, "2026-01-31", null, null, null, null, null));
        assertThat(toOnly.from()).as("잘못된 시간대는 서비스 기본(서울)").isEqualTo(Instant.parse("2026-01-24T15:00:00Z"));
    }

    @Test
    void invalidPeriods() {
        assertThat(fieldError(new Query("2026-02-01", "2026-01-01", null, null, null, null, null)))
                .isEqualTo(new FieldError("from", "INVALID", Map.of()));
        assertThat(fieldError(new Query("2025-01-01", "2026-01-02", null, null, null, null, null)))
                .isEqualTo(new FieldError("to", "INVALID", Map.of("maxDays", 366)));
        assertThat(fieldError(new Query("2026-13-01", null, null, null, null, null, null)))
                .isEqualTo(new FieldError("from", "INVALID_FORMAT", Map.of()));
        search(1L, new Query("2025-01-02", "2026-01-02", null, null, null, null, null));
    }

    @Test
    void detailShowsIpOnlyToSuperAdmin() {
        User admin = TestEntities.user(5L);
        AdminAuditLog log = TestEntities.with(new AdminAuditLog(admin, AuditActions.ROLE_GRANT,
                AuditActions.TARGET_USER, 7L, null, Map.of("role", "USER"), Map.of("role", "ADMIN"), null,
                "203.0.113.9"), "id", 3L);
        when(repository.findWithAdmin(3L)).thenReturn(Optional.of(log));
        when(users.findRoleAndStatusById(1L)).thenReturn(Optional.of(role(UserRole.SUPER_ADMIN, UserStatus.ACTIVE)));
        when(users.findRoleAndStatusById(2L)).thenReturn(Optional.of(role(UserRole.ADMIN, UserStatus.ACTIVE)));
        when(users.findRoleAndStatusById(4L)).thenReturn(Optional.of(role(UserRole.SUPER_ADMIN,
                UserStatus.SUSPENDED)));

        AuditLogDetailResponse root = service.detail(1L, 3L);
        assertThat(root.requestIp()).isEqualTo("203.0.113.9");
        assertThat(root.admin().userId()).isEqualTo(5L);
        assertThat(root.after()).containsEntry("role", "ADMIN");
        assertThat(service.detail(2L, 3L).requestIp()).isNull();
        assertThat(service.detail(4L, 3L).requestIp()).isNull();
        assertThat(service.detail(6L, 3L).requestIp()).isNull();

        BusinessException missing = catchThrowableOfType(BusinessException.class, () -> service.detail(1L, 99L));
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void actionsListsCodeConstants() {
        assertThat(service.actions().actions()).isEqualTo(AuditActions.ALL);
        assertThat(service.actions().targetTypes()).isEqualTo(AuditActions.TARGETS);
    }

    private Criteria search(long requester, Query query) {
        service.list(requester, query, PAGE);
        ArgumentCaptor<Criteria> captor = ArgumentCaptor.forClass(Criteria.class);
        verify(repository, org.mockito.Mockito.atLeastOnce()).search(captor.capture(), eq(PAGE));
        List<Criteria> all = captor.getAllValues();
        return all.get(all.size() - 1);
    }

    private FieldError fieldError(Query query) {
        BusinessException e = catchThrowableOfType(BusinessException.class, () -> service.list(1L, query, PAGE));
        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        return e.fieldErrors().get(0);
    }

    private static AdminUserRepository.RoleAndStatus role(UserRole role, UserStatus status) {
        return new AdminUserRepository.RoleAndStatus() {
            @Override
            public UserRole getRole() {
                return role;
            }

            @Override
            public UserStatus getStatus() {
                return status;
            }
        };
    }
}
