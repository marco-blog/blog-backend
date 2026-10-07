package net.java21.blog.backend.admin.audit;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.java21.blog.backend.admin.SuperAdminGuard;
import net.java21.blog.backend.admin.audit.dto.AuditActionListResponse;
import net.java21.blog.backend.admin.audit.dto.AuditLogDetailResponse;
import net.java21.blog.backend.admin.audit.dto.AuditLogEntryResponse;
import net.java21.blog.backend.admin.user.AdminUserRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작업 기록 조회(006 FR-106, research A6). 기간({@code YYYY-MM-DD})은 요청한 관리자의 시간대로 해석해 {@code from} 0시부터
 * {@code to} 다음 날 0시 전까지이고, 생략하면 최근 7일(오늘 포함), 최대 {@value #MAX_DAYS}일이다. 상세의 요청 IP는 요청자가 DB 기준
 * 최고 관리자일 때만 준다. 기록을 바꾸거나 지우는 기능은 없다(1년 정리는 {@code AdminAuditPurgeJob}만).
 */
@Service
public class AdminAuditLogService {

    static final int DEFAULT_DAYS = 7;
    static final int MAX_DAYS = 366;

    private final AdminAuditLogQueryRepository repository;
    private final AdminUserRepository adminUserRepository;
    private final Clock clock;

    public AdminAuditLogService(AdminAuditLogQueryRepository repository, AdminUserRepository adminUserRepository,
            Clock clock) {
        this.repository = repository;
        this.adminUserRepository = adminUserRepository;
        this.clock = clock;
    }

    /** 목록 조건(화면·API 쿼리 그대로). {@code action}은 쉼표로 여러 개. */
    public record Query(String from, String to, Long adminId, String action, String targetType, Long targetId,
            String targetKey) {
    }

    @Transactional(readOnly = true)
    public Page<AuditLogEntryResponse> list(long requesterId, Query query, Pageable pageable) {
        ZoneId zone = zoneOf(requesterId);
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        LocalDate from = date("from", query.from());
        LocalDate to = date("to", query.to());
        if (to == null) {
            to = from == null ? today : from.plusDays(DEFAULT_DAYS - 1L).isAfter(today) ? today
                    : from.plusDays(DEFAULT_DAYS - 1L);
        }
        if (from == null) {
            from = to.minusDays(DEFAULT_DAYS - 1L);
        }
        if (from.isAfter(to)) {
            throw invalid("from", "INVALID", Map.of());
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw invalid("to", "INVALID", Map.of("maxDays", MAX_DAYS));
        }
        AdminAuditLogQueryRepository.Criteria criteria = new AdminAuditLogQueryRepository.Criteria(
                from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant(), query.adminId(),
                actions(query.action()), blankToNull(query.targetType()), query.targetId(),
                blankToNull(query.targetKey()));
        return repository.search(criteria, pageable);
    }

    @Transactional(readOnly = true)
    public AuditLogDetailResponse detail(long requesterId, long id) {
        AdminAuditLog log = repository.findWithAdmin(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Audit log not found: " + id));
        String requestIp = isSuperAdmin(requesterId) ? log.getRequestIp() : null;
        return new AuditLogDetailResponse(log.getId(),
                new AuditLogEntryResponse.AdminName(log.getAdmin().getId(), log.getAdmin().getNickname()),
                log.getAction(), log.getTargetType(), log.getTargetId(), log.getTargetKey(), log.getBefore(),
                log.getAfter(), log.getReason(), log.getCreatedAt(), requestIp);
    }

    public AuditActionListResponse actions() {
        return new AuditActionListResponse(AuditActions.ALL, AuditActions.TARGETS);
    }

    /**
     * 요청자가 지금(DB 기준) 활성 최고 관리자인지. {@link SuperAdminGuard#requireSuperAdmin}의 예외를 잡으면 같은 트랜잭션이
     * rollback-only가 되므로 같은 조건을 직접 읽는다.
     */
    private boolean isSuperAdmin(long userId) {
        return adminUserRepository.findRoleAndStatusById(userId)
                .filter(r -> r.getRole() == UserRole.SUPER_ADMIN && r.getStatus() == UserStatus.ACTIVE)
                .isPresent();
    }

    private ZoneId zoneOf(long userId) {
        try {
            return ZoneId.of(adminUserRepository.findTimeZoneById(userId).orElse(User.DEFAULT_TIME_ZONE));
        } catch (DateTimeException e) {
            return ZoneId.of(User.DEFAULT_TIME_ZONE);
        }
    }

    private static LocalDate date(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw invalid(field, "INVALID_FORMAT", Map.of());
        }
    }

    private static List<String> actions(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip).filter(a -> !a.isEmpty())
                .map(a -> a.toUpperCase(Locale.ROOT)).distinct().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static BusinessException invalid(String field, String code, Map<String, Object> params) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid audit log query: " + field,
                List.of(new FieldError(field, code, params)));
    }
}
