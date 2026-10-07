package net.java21.blog.backend.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.comment.event.CommentCreatedEvent;
import net.java21.blog.backend.export.event.BlogExportReadyEvent;
import net.java21.blog.backend.notification.NotificationsProperties;
import net.java21.blog.backend.notification.domain.Notification;
import net.java21.blog.backend.notification.domain.NotificationTargetType;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.report.event.ReportResolvedEvent;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
import net.java21.blog.backend.notification.repository.NotificationRepository;
import net.java21.blog.backend.subscription.event.BlogSubscribedEvent;
import net.java21.blog.backend.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 만들기(002 FR-033, research D3). 댓글·구독 트랜잭션이 커밋된 뒤에만, 새 트랜잭션에서 한 번 만든다(롤백되면 만들지 않음).
 * 알림을 일으킨 회원과 받는 회원이 같으면 만들지 않는다. 알림 저장이 실패해도 댓글·구독은 이미 성공했으므로 로그만 남긴다.
 * <p>새 트랜잭션(REQUIRES_NEW)은 {@link TransactionTemplate}으로 연다. {@code @Transactional} 메서드 안에서 예외를 잡으면
 * 트랜잭션이 rollback-only로 남아 커밋 때 다시 예외가 나므로, 트랜잭션 바깥에서 잡는다.
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationRepository notificationRepository;
    private final NotificationQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final BlogRepository blogRepository;
    private final NotificationsProperties properties;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public NotificationEventListener(NotificationRepository notificationRepository,
            NotificationQueryRepository queryRepository, UserRepository userRepository, BlogRepository blogRepository,
            NotificationsProperties properties, Clock clock, PlatformTransactionManager transactionManager) {
        this.notificationRepository = notificationRepository;
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
        this.blogRepository = blogRepository;
        this.properties = properties;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 댓글·답글 → 글이 속한 블로그의 주인에게 NEW_COMMENT. 작성자가 주인이면 만들지 않는다. 비회원 댓글(004)은 {@code actor} 없이
     * {@code params.guestName}을 싣는다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommentCreated(CommentCreatedEvent event) {
        if (event.authorId() != null && event.authorId() == event.blogOwnerId()) {
            return;
        }
        try {
            requiresNew.executeWithoutResult(status -> {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("postId", event.postId());
                params.put("postTitle", event.postTitle());
                if (event.authorId() == null) {
                    params.put("guestName", event.guestName());
                }
                notificationRepository.saveAndFlush(new Notification(
                        userRepository.getReferenceById(event.blogOwnerId()),
                        event.authorId() == null ? null : userRepository.getReferenceById(event.authorId()),
                        blogRepository.getReferenceById(event.blogId()), NotificationType.NEW_COMMENT,
                        NotificationTargetType.COMMENT, event.commentId(), params));
            });
        } catch (RuntimeException e) {
            log.warn("NEW_COMMENT notification failed: commentId={}, error={}", event.commentId(), e.toString());
        }
    }

    /**
     * 구독 → 블로그 주인에게 NEW_SUBSCRIBER. 같은 구독자·블로그의 NEW_SUBSCRIBER가
     * {@code blog.notifications.subscriber-dedup-window} 안에 있으면 만들지 않는다(구독·취소 반복 방지).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlogSubscribed(BlogSubscribedEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> createSubscriberNotification(event));
        } catch (RuntimeException e) {
            log.warn("NEW_SUBSCRIBER notification failed: blogId={}, subscriberId={}, error={}", event.blogId(),
                    event.subscriberId(), e.toString());
        }
    }

    private void createSubscriberNotification(BlogSubscribedEvent event) {
        Optional<Blog> found = blogRepository.findById(event.blogId());
        if (found.isEmpty()) {
            return;
        }
        Blog blog = found.get();
        long ownerId = blog.getUser().getId();
        if (ownerId == event.subscriberId()) {
            return;
        }
        Instant since = clock.instant().minus(properties.subscriberDedupWindow());
        if (queryRepository.existsSubscriberNotificationSince(ownerId, event.subscriberId(), blog.getId(), since)) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("blogTitle", blog.getTitle());
        notificationRepository.saveAndFlush(new Notification(userRepository.getReferenceById(ownerId),
                userRepository.getReferenceById(event.subscriberId()), blog, NotificationType.NEW_SUBSCRIBER,
                NotificationTargetType.BLOG, blog.getId(), params));
    }

    /**
     * 백업 준비(004 FR-145) → 요청한 주인에게 BACKUP_READY(target BLOG_EXPORT, params {@code { blogTitle, handle, expiresAt }}).
     * 행위자는 없다(시스템).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBlogExportReady(BlogExportReadyEvent event) {
        try {
            requiresNew.executeWithoutResult(status -> {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("blogTitle", event.blogTitle());
                params.put("handle", event.handle());
                params.put("expiresAt", event.expiresAt() == null ? null : event.expiresAt().toString());
                notificationRepository.saveAndFlush(new Notification(
                        userRepository.getReferenceById(event.requestedBy()), null,
                        blogRepository.getReferenceById(event.blogId()), NotificationType.BACKUP_READY,
                        NotificationTargetType.BLOG_EXPORT, event.exportId(), params));
            });
        } catch (RuntimeException e) {
            log.warn("BACKUP_READY notification failed: exportId={}, error={}", event.exportId(), e.toString());
        }
    }

    /**
     * 005 신고 처리 → 회원 신고자마다 REPORT_RESOLVED(target REPORT/그 신고 id, params {@code { targetType, decision }}, 행위자·블로그
     * 없음). 대상의 제목·내용은 넣지 않는다(신고자가 볼 수 없게 된 내용을 알림이 드러내지 않게).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReportResolved(ReportResolvedEvent event) {
        for (ReportResolvedEvent.MemberRecipient recipient : event.members()) {
            try {
                requiresNew.executeWithoutResult(status -> {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("targetType", event.targetType() == null ? null : event.targetType().name());
                    params.put("decision", event.decision().name());
                    notificationRepository.saveAndFlush(new Notification(
                            userRepository.getReferenceById(recipient.reporterId()), null, null,
                            NotificationType.REPORT_RESOLVED, NotificationTargetType.REPORT, recipient.reportId(),
                            params));
                });
            } catch (RuntimeException e) {
                log.warn("REPORT_RESOLVED notification failed: reportId={}, error={}", recipient.reportId(),
                        e.toString());
            }
        }
    }
}
