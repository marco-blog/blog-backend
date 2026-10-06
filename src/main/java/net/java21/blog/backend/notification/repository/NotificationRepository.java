package net.java21.blog.backend.notification.repository;

import net.java21.blog.backend.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

/** 알림 저장(커밋 후 이벤트 리스너). 조회·읽음·정리는 {@link NotificationQueryRepository}. */
public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
