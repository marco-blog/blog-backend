package net.java21.blog.backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.repository.BlogRepository;
import net.java21.blog.backend.comment.event.CommentCreatedEvent;
import net.java21.blog.backend.notification.NotificationsProperties;
import net.java21.blog.backend.export.event.BlogExportReadyEvent;
import net.java21.blog.backend.notification.domain.Notification;
import net.java21.blog.backend.notification.domain.NotificationTargetType;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
import net.java21.blog.backend.notification.repository.NotificationRepository;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.event.ReportResolvedEvent;
import net.java21.blog.backend.subscription.event.BlogSubscribedEvent;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 알림 만들기(T042, 002 research D3): 댓글 → 블로그 주인에게 NEW_COMMENT(작성자가 주인이면 없음, 답글도 같은 규칙),
 * 구독 → NEW_SUBSCRIBER(24시간 안의 같은 알림이 있으면 없음), 저장 실패는 로그만 남기고 던지지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationEventListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final long OWNER = 1L;
    private static final long READER = 2L;

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private NotificationQueryRepository queryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private BlogRepository blogRepository;
    @Mock
    private PlatformTransactionManager transactionManager;

    private final MutableClock clock = new MutableClock(NOW);
    private NotificationEventListener listener;
    private User owner;
    private User reader;
    private Blog blog;

    @BeforeEach
    void setUp() {
        listener = new NotificationEventListener(notificationRepository, queryRepository, userRepository,
                blogRepository, new NotificationsProperties(Duration.ofDays(90), Duration.ofHours(24)), clock,
                transactionManager);
        owner = TestEntities.user(OWNER);
        reader = TestEntities.user(READER);
        blog = TestEntities.blog(10L, owner, "marco");
        TestEntities.with(blog, "title", "마르코의 블로그");
        when(userRepository.getReferenceById(OWNER)).thenReturn(owner);
        when(userRepository.getReferenceById(READER)).thenReturn(reader);
        when(blogRepository.getReferenceById(10L)).thenReturn(blog);
        when(blogRepository.findById(10L)).thenReturn(Optional.of(blog));
    }

    private Notification saved() {
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void commentNotifiesBlogOwner() {
        listener.onCommentCreated(new CommentCreatedEvent(3001L, 123L, "첫 글", 10L, OWNER, READER));

        Notification n = saved();
        assertThat(n.getUser()).isSameAs(owner);
        assertThat(n.getActor()).isSameAs(reader);
        assertThat(n.getBlog()).isSameAs(blog);
        assertThat(n.getType()).isEqualTo(NotificationType.NEW_COMMENT);
        assertThat(n.getTargetType()).isEqualTo(NotificationTargetType.COMMENT);
        assertThat(n.getTargetId()).isEqualTo(3001L);
        assertThat(n.getParams()).isEqualTo(Map.of("postId", 123L, "postTitle", "첫 글"));
        assertThat(n.getReadAt()).isNull();
    }

    /** 004 비회원 댓글(T084): 글 주인에게 NEW_COMMENT, 행위자 없음, {@code params.guestName}. */
    @Test
    void guestCommentNotifiesBlogOwnerWithGuestName() {
        listener.onCommentCreated(new CommentCreatedEvent(3003L, 123L, "첫 글", 10L, OWNER, null, "손님"));

        Notification n = saved();
        assertThat(n.getUser()).isSameAs(owner);
        assertThat(n.getActor()).isNull();
        assertThat(n.getType()).isEqualTo(NotificationType.NEW_COMMENT);
        assertThat(n.getTargetId()).isEqualTo(3003L);
        assertThat(n.getParams()).isEqualTo(Map.of("postId", 123L, "postTitle", "첫 글", "guestName", "손님"));
    }

    /** 004 백업 준비(T104): 요청한 주인에게 BACKUP_READY, target BLOG_EXPORT, 행위자 없음. */
    @Test
    void exportReadyNotifiesTheRequester() {
        Instant expires = NOW.plus(Duration.ofDays(7));
        listener.onBlogExportReady(new BlogExportReadyEvent(3L, OWNER, 10L, "마르코의 블로그", "marco", expires));

        Notification n = saved();
        assertThat(n.getUser()).isSameAs(owner);
        assertThat(n.getActor()).isNull();
        assertThat(n.getBlog()).isSameAs(blog);
        assertThat(n.getType()).isEqualTo(NotificationType.BACKUP_READY);
        assertThat(n.getTargetType()).isEqualTo(NotificationTargetType.BLOG_EXPORT);
        assertThat(n.getTargetId()).isEqualTo(3L);
        assertThat(n.getParams()).isEqualTo(Map.of("blogTitle", "마르코의 블로그", "handle", "marco", "expiresAt",
                expires.toString()));
    }

    /** 005 신고 처리(T042): 회원 신고자마다 REPORT_RESOLVED, target REPORT, 행위자·블로그 없음, 대상 내용 없음. */
    @Test
    void reportResolvedNotifiesEachMemberReporter() {
        listener.onReportResolved(new ReportResolvedEvent(ReportTargetType.COMMENT, ReportStatus.ACTIONED,
                List.of(new ReportResolvedEvent.MemberRecipient(41L, READER),
                        new ReportResolvedEvent.MemberRecipient(42L, OWNER)),
                List.of(new ReportResolvedEvent.RightsRecipient(43L, "me@example.com", "https://x.example"))));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).saveAndFlush(captor.capture());
        Notification first = captor.getAllValues().getFirst();
        assertThat(first.getUser()).isSameAs(reader);
        assertThat(first.getActor()).isNull();
        assertThat(first.getBlog()).isNull();
        assertThat(first.getType()).isEqualTo(NotificationType.REPORT_RESOLVED);
        assertThat(first.getTargetType()).isEqualTo(NotificationTargetType.REPORT);
        assertThat(first.getTargetId()).isEqualTo(41L);
        assertThat(first.getParams()).isEqualTo(Map.of("targetType", "COMMENT", "decision", "ACTIONED"));
        assertThat(captor.getAllValues().get(1).getUser()).isSameAs(owner);
    }

    @Test
    void reportResolvedFailureForOneReporterDoesNotStopTheOthers() {
        when(notificationRepository.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"))
                .thenReturn(null);
        assertThatCode(() -> listener.onReportResolved(new ReportResolvedEvent(null, ReportStatus.DISMISSED,
                List.of(new ReportResolvedEvent.MemberRecipient(41L, READER),
                        new ReportResolvedEvent.MemberRecipient(42L, OWNER)), List.of())))
                .doesNotThrowAnyException();
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getAllValues().get(1).getParams()).containsEntry("targetType", null)
                .containsEntry("decision", "DISMISSED");
    }

    @Test
    void exportReadyFailureIsLoggedNotThrown() {
        when(notificationRepository.saveAndFlush(any())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> listener.onBlogExportReady(new BlogExportReadyEvent(3L, OWNER, 10L, "t", "marco", null)))
                .doesNotThrowAnyException();
    }

    @Test
    void ownCommentOrReplyCreatesNothing() {
        listener.onCommentCreated(new CommentCreatedEvent(3002L, 123L, "첫 글", 10L, OWNER, OWNER));

        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void subscriptionNotifiesBlogOwnerOncePerWindow() {
        listener.onBlogSubscribed(new BlogSubscribedEvent(READER, 10L));

        Notification n = saved();
        assertThat(n.getUser()).isSameAs(owner);
        assertThat(n.getActor()).isSameAs(reader);
        assertThat(n.getBlog()).isSameAs(blog);
        assertThat(n.getType()).isEqualTo(NotificationType.NEW_SUBSCRIBER);
        assertThat(n.getTargetType()).isEqualTo(NotificationTargetType.BLOG);
        assertThat(n.getTargetId()).isEqualTo(10L);
        assertThat(n.getParams()).isEqualTo(Map.of("blogTitle", "마르코의 블로그"));
        verify(queryRepository).existsSubscriberNotificationSince(OWNER, READER, 10L, NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void subscriptionWithinDedupWindowCreatesNothing() {
        clock.advance(Duration.ofHours(30));
        when(queryRepository.existsSubscriberNotificationSince(eq(OWNER), eq(READER), eq(10L), any()))
                .thenReturn(true);

        listener.onBlogSubscribed(new BlogSubscribedEvent(READER, 10L));

        verify(queryRepository).existsSubscriberNotificationSince(OWNER, READER, 10L,
                NOW.plus(Duration.ofHours(6)));
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void missingBlogOrOwnSubscriptionCreatesNothing() {
        when(blogRepository.findById(99L)).thenReturn(Optional.empty());
        listener.onBlogSubscribed(new BlogSubscribedEvent(READER, 99L));
        listener.onBlogSubscribed(new BlogSubscribedEvent(OWNER, 10L));

        verify(queryRepository, never()).existsSubscriberNotificationSince(anyLong(), anyLong(), anyLong(), any());
        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    void saveFailureIsLoggedNotThrown() {
        when(notificationRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("boom"));

        assertThatCode(() -> listener.onCommentCreated(new CommentCreatedEvent(3001L, 123L, "첫 글", 10L, OWNER,
                READER))).doesNotThrowAnyException();
        assertThatCode(() -> listener.onBlogSubscribed(new BlogSubscribedEvent(READER, 10L)))
                .doesNotThrowAnyException();
    }
}
