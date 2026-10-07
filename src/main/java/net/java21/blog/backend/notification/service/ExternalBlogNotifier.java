package net.java21.blog.backend.notification.service;

import java.util.Map;

import net.java21.blog.backend.notification.domain.Notification;
import net.java21.blog.backend.notification.domain.NotificationTargetType;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.notification.repository.NotificationRepository;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 007 외부 블로그 알림(승인·거절·자동 중지, contracts/api.md "알림"). 상태 변경과 같은 트랜잭션에서 쓴다(변경이 롤백되면 알림도 없음).
 * 행위자·블로그는 없다.
 */
@Component
public class ExternalBlogNotifier {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public ExternalBlogNotifier(NotificationRepository notificationRepository, UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyExternalBlog(Long userId, NotificationType type, Long externalBlogId, Map<String, Object> params) {
        notificationRepository.save(new Notification(userRepository.getReferenceById(userId), null, null, type,
                NotificationTargetType.EXTERNAL_BLOG, externalBlogId, params));
    }
}
