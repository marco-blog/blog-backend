package net.java21.blog.backend.admin.user;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.admin.SuperAdminGuard;
import net.java21.blog.backend.admin.audit.AdminAuditService;
import net.java21.blog.backend.admin.audit.AuditActions;
import net.java21.blog.backend.auth.repository.RefreshTokenRepository;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.security.SuspendedUserRegistry;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserRole;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 회원 정지·해제(005 FR-042, research M5, 006 FR-104·105).
 * <ul>
 *   <li>ACTIVE만 정지한다(이미 정지면 그대로, 탈퇴면 409 {@code USER_NOT_ACTIVE}). 자기 자신은 422 {@code CANNOT_SUSPEND_SELF}.</li>
 *   <li>관리자(ADMIN·SUPER_ADMIN) 정지는 SUPER_ADMIN만(아니면 403), 마지막 활성 SUPER_ADMIN은 정지할 수 없다(409
 *       {@code LAST_SUPER_ADMIN}, {@link SuperAdminGuard}).</li>
 *   <li>정지는 회원 행 잠금 안에서 상태를 바꾸고 모든 갱신 토큰을 폐기하며, 커밋 뒤 {@link SuspendedUserRegistry}에 올려 이미 발급된 접근
 *       토큰도 다음 요청부터 인증하지 않게 한다. 해제는 ACTIVE로 되돌리고 목록에서 뺀다.</li>
 *   <li>상태가 바뀐 경우에만 작업 기록 {@code USER_SUSPEND}·{@code USER_UNSUSPEND}(before·after {@code status}, 사유)을 남긴다.</li>
 * </ul>
 */
@Service
public class SuspensionService {

    public static final int REASON_MAX = 500;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SuperAdminGuard superAdminGuard;
    private final SuspendedUserRegistry registry;
    private final AdminAuditService auditService;
    private final Clock clock;

    public SuspensionService(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
            SuperAdminGuard superAdminGuard, SuspendedUserRegistry registry, AdminAuditService auditService,
            Clock clock) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.superAdminGuard = superAdminGuard;
        this.registry = registry;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** @return 정지로 상태가 바뀌었으면 true(이미 정지면 false) */
    @Transactional
    public boolean suspend(long adminId, long userId, String reason, String requestIp) {
        String normalized = reason(reason, true, "reason");
        if (adminId == userId) {
            throw new BusinessException(ErrorCode.CANNOT_SUSPEND_SELF, "Cannot suspend yourself");
        }
        User target = userRepository.findById(userId).orElseThrow(() -> notFound(userId));
        if (target.getStatus() == UserStatus.WITHDRAWN) {
            throw new BusinessException(ErrorCode.USER_NOT_ACTIVE, "User is not active: " + userId);
        }
        if (target.isSuspended()) {
            return false;
        }
        if (target.getRole() != UserRole.USER) {
            superAdminGuard.requireSuperAdmin(adminId);
            if (target.getRole() == UserRole.SUPER_ADMIN) {
                superAdminGuard.ensureAnotherActiveSuperAdmin(userId);
            }
        }
        User locked = userRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));
        if (!locked.suspend()) {
            return false;
        }
        refreshTokenRepository.revokeAllByUserId(userId, clock.instant());
        auditService.record(adminId, AuditActions.USER_SUSPEND, AuditActions.TARGET_USER, userId, null,
                status(UserStatus.ACTIVE), status(UserStatus.SUSPENDED), normalized, requestIp);
        afterCommit(() -> registry.add(userId));
        return true;
    }

    /** @return 해제로 상태가 바뀌었으면 true(정지가 아니면 false) */
    @Transactional
    public boolean unsuspend(long adminId, long userId, String reason, String requestIp) {
        String normalized = reason(reason, false, "reason");
        User target = userRepository.findByIdForUpdate(userId).orElseThrow(() -> notFound(userId));
        if (!target.unsuspend()) {
            return false;
        }
        auditService.record(adminId, AuditActions.USER_UNSUSPEND, AuditActions.TARGET_USER, userId, null,
                status(UserStatus.SUSPENDED), status(UserStatus.ACTIVE), normalized, requestIp);
        afterCommit(() -> registry.remove(userId));
        return true;
    }

    /** 사유: 앞뒤 공백 제거, 1~500자. 필수가 아니면 비어도 된다(null). */
    public static String reason(String raw, boolean required, String field) {
        String reason = raw == null ? "" : raw.strip();
        if (reason.isEmpty()) {
            if (required) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                        List.of(FieldError.of(field, "REQUIRED")));
            }
            return null;
        }
        if (reason.codePointCount(0, reason.length()) > REASON_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Validation failed",
                    List.of(new FieldError(field, "TOO_LONG", Map.of("max", REASON_MAX))));
        }
        return reason;
    }

    private static Map<String, Object> status(UserStatus status) {
        return Map.of("status", status.name());
    }

    private static BusinessException notFound(long userId) {
        return new BusinessException(ErrorCode.USER_NOT_FOUND, "User not found: " + userId);
    }

    /** 커밋 뒤에 실행한다(트랜잭션 밖이면 바로). */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
