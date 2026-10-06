package net.java21.blog.backend.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.notification.domain.NotificationTargetType;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.notification.dto.BulkNotificationRequest;
import net.java21.blog.backend.notification.dto.NotificationBulkAction;
import net.java21.blog.backend.notification.dto.NotificationResponse;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
import net.java21.blog.backend.notification.repository.NotificationRow;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 내 알림 목록·읽음(T044, 002 contracts/api.md 알림 절): 목록 매핑(탈퇴 actor, actor 없음), 남의·없는 알림 404,
 * 이미 읽음이면 그대로, 일괄 ids 100개 초과·action 없음 400.
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @Mock
    private NotificationQueryRepository queryRepository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(queryRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static NotificationRow row(long id, Long actorId, UserStatus status, Instant readAt) {
        return new NotificationRow(id, NotificationType.NEW_COMMENT, actorId, actorId == null ? null : "독자", status,
                actorId == null ? null : "k3Jd9fQ2xLmA7pZ0bR5tYw", "marco", "마르코의 블로그",
                NotificationTargetType.COMMENT, 3001L, Map.of("postId", 123, "postTitle", "첫 글"), readAt,
                NOW.minusSeconds(60));
    }

    @Test
    void listMapsActorBlogAndReadState() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(queryRepository.findMine(7L, pageable)).thenReturn(new PageImpl<>(List.of(
                row(1L, 2L, UserStatus.ACTIVE, null),
                row(2L, 3L, UserStatus.WITHDRAWN, NOW),
                new NotificationRow(3L, NotificationType.NEW_SUBSCRIBER, null, null, null, null, null, null, null,
                        null, null, null, NOW)), pageable, 3));

        List<NotificationResponse> list = service.list(7L, pageable).getContent();

        NotificationResponse active = list.getFirst();
        assertThat(active.type()).isEqualTo("NEW_COMMENT");
        assertThat(active.actor()).isEqualTo(new NotificationResponse.Actor(2L, "독자",
                "/media/k3Jd9fQ2xLmA7pZ0bR5tYw", false));
        assertThat(active.blog()).isEqualTo(new NotificationResponse.BlogRef("marco", "마르코의 블로그"));
        assertThat(active.targetType()).isEqualTo("COMMENT");
        assertThat(active.targetId()).isEqualTo(3001L);
        assertThat(active.params()).containsEntry("postTitle", "첫 글");
        assertThat(active.read()).isFalse();
        assertThat(active.createdAt()).isEqualTo(NOW.minusSeconds(60));

        NotificationResponse withdrawn = list.get(1);
        assertThat(withdrawn.actor()).isEqualTo(new NotificationResponse.Actor(3L, null, null, true));
        assertThat(withdrawn.read()).isTrue();

        NotificationResponse system = list.get(2);
        assertThat(system.actor()).isNull();
        assertThat(system.blog()).isNull();
        assertThat(system.targetType()).isNull();
        assertThat(system.params()).isEmpty();
    }

    @Test
    void countUnreadDelegates() {
        when(queryRepository.countUnread(7L)).thenReturn(4L);

        assertThat(service.countUnread(7L)).isEqualTo(4);
    }

    @Test
    void readMarksUnreadOnce() {
        when(queryRepository.findMine(7L, 1L)).thenReturn(Optional.of(row(1L, 2L, UserStatus.ACTIVE, null)));

        NotificationResponse read = service.read(7L, 1L);

        assertThat(read.read()).isTrue();
        verify(queryRepository).markRead(7L, List.of(1L), NOW);
    }

    @Test
    void readOfAlreadyReadKeepsIt() {
        when(queryRepository.findMine(7L, 1L)).thenReturn(Optional.of(row(1L, 2L, UserStatus.ACTIVE, NOW)));

        assertThat(service.read(7L, 1L).read()).isTrue();
        verify(queryRepository, never()).markRead(anyLong(), any(), any());
    }

    @Test
    void readOfOthersOrMissingIsNotFound() {
        when(queryRepository.findMine(7L, 9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read(7L, 9L))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Test
    void bulkMarksGivenOrAllUnread() {
        when(queryRepository.markRead(7L, List.of(1L, 2L), NOW)).thenReturn(2L);
        when(queryRepository.markRead(7L, null, NOW)).thenReturn(5L);

        assertThat(service.bulk(7L, new BulkNotificationRequest(NotificationBulkAction.MARK_READ, List.of(1L, 2L)))
                .updated()).isEqualTo(2);
        assertThat(service.bulk(7L, new BulkNotificationRequest(NotificationBulkAction.MARK_READ, null)).updated())
                .isEqualTo(5);
    }

    @Test
    void bulkRejectsTooManyIdsOrMissingAction() {
        List<Long> tooMany = LongStream.rangeClosed(1, 101).boxed().toList();

        assertThatThrownBy(() -> service.bulk(7L, new BulkNotificationRequest(NotificationBulkAction.MARK_READ,
                tooMany)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement().satisfies(f -> {
                        assertThat(f.field()).isEqualTo("ids");
                        assertThat(f.code()).isEqualTo("TOO_LONG");
                    });
                });
        assertThatThrownBy(() -> service.bulk(7L, new BulkNotificationRequest(null, List.of(1L))))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement()
                            .satisfies(f -> assertThat(f.field()).isEqualTo("action"));
                });
        verify(queryRepository, never()).markRead(anyLong(), any(), any());
    }
}
