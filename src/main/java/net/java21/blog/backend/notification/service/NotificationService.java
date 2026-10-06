package net.java21.blog.backend.notification.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.notification.dto.BulkNotificationRequest;
import net.java21.blog.backend.notification.dto.BulkNotificationResponse;
import net.java21.blog.backend.notification.dto.NotificationBulkAction;
import net.java21.blog.backend.notification.dto.NotificationResponse;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
import net.java21.blog.backend.notification.repository.NotificationRow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 내 알림 목록·읽음 처리(002 FR-033, contracts/api.md 알림 절). 다른 회원의 알림은 "없는 알림"과 같은 404다. */
@Service
public class NotificationService {

    private final NotificationQueryRepository queryRepository;
    private final Clock clock;

    public NotificationService(NotificationQueryRepository queryRepository, Clock clock) {
        this.queryRepository = queryRepository;
        this.clock = clock;
    }

    /** 내 알림, 최신순. 쿼리 2회(목록, 전체 수). */
    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(long userId, Pageable pageable) {
        return queryRepository.findMine(userId, pageable).map(NotificationResponse::of);
    }

    /** 안 읽은 알림 수(상단 배지). */
    @Transactional(readOnly = true)
    public long countUnread(long userId) {
        return queryRepository.countUnread(userId);
    }

    /** 알림 하나를 읽음으로. 이미 읽었으면 그대로 둔다. */
    @Transactional
    public NotificationResponse read(long userId, long notificationId) {
        NotificationRow row = queryRepository.findMine(userId, notificationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND,
                        "Notification not found: " + notificationId));
        if (row.readAt() == null) {
            queryRepository.markRead(userId, List.of(notificationId), clock.instant());
        }
        return NotificationResponse.of(row, true);
    }

    /** 일괄 읽음. {@code ids}가 없으면 안 읽은 알림 전부, 있으면 그중 내 알림만(최대 100개). */
    @Transactional
    public BulkNotificationResponse bulk(long userId, BulkNotificationRequest request) {
        // 모르는 action 문자열은 본문 해석 단계에서 400이 된다(MARK_READ만 있다).
        if (request.action() != NotificationBulkAction.MARK_READ) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Bulk action is required",
                    List.of(FieldError.of("action", "REQUIRED")));
        }
        List<Long> ids = request.ids();
        if (ids != null && ids.size() > BulkNotificationRequest.MAX_IDS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Too many ids: " + ids.size(),
                    List.of(new FieldError("ids", "TOO_LONG", Map.of("max", BulkNotificationRequest.MAX_IDS))));
        }
        return new BulkNotificationResponse(queryRepository.markRead(userId, ids, clock.instant()));
    }
}
