package net.java21.blog.backend.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.notification.domain.Notification;
import net.java21.blog.backend.notification.domain.NotificationTargetType;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * 알림 조회·읽음·정리(T041, 002 FR-033, data-model notifications): 내 알림 최신순 페이지와 일으킨 회원·블로그를 알림 수와 무관한
 * 쿼리 수로(목록 1 + 수 1), 안 읽은 수, 내 알림만 읽음, 일괄 읽음, NEW_SUBSCRIBER 중복 확인, 오래된 알림·회원 알림 삭제.
 */
@JpaRepositoryTest
@Import({NotificationQueryRepository.class, NotificationQueryRepositoryTest.Config.class})
class NotificationQueryRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    private EntityManager em;
    @Autowired
    private NotificationQueryRepository repository;
    @Autowired
    private QueryCounter queryCounter;
    @Autowired
    private MutableClock clock;

    private JpaFixtures fx;
    private User owner;
    private User other;
    private User reader;
    private Blog blog;

    @BeforeEach
    void setUp() {
        clock.set(NOW);
        fx = new JpaFixtures(em);
        owner = fx.user("owner");
        other = fx.user("other");
        reader = fx.user("reader");
        blog = fx.blog(owner, "marco");
    }

    private Notification comment(User receiver, User actor, long commentId) {
        Notification n = new Notification(receiver, actor, blog, NotificationType.NEW_COMMENT,
                NotificationTargetType.COMMENT, commentId, Map.of("postId", 10, "postTitle", "첫 글"));
        em.persist(n);
        clock.advance(Duration.ofSeconds(1));
        return n;
    }

    private Notification subscriber(User receiver, User actor) {
        Notification n = new Notification(receiver, actor, blog, NotificationType.NEW_SUBSCRIBER,
                NotificationTargetType.BLOG, blog.getId(), Map.of("blogTitle", "마르코의 블로그"));
        em.persist(n);
        clock.advance(Duration.ofSeconds(1));
        return n;
    }

    @Test
    void myNotificationsNewestFirstWithActorAndBlogInTwoQueries() {
        Media profile = new Media(reader, "readerprofile000000000", MediaPurpose.PROFILE, "p.png", "2026/10/p.png",
                "image/png", 10, 1, 1);
        TestEntities.with(profile, "status", MediaStatus.ATTACHED);
        em.persist(profile);
        reader.changeProfileMedia(profile);
        User withdrawn = fx.user("gone");
        TestEntities.with(withdrawn, "status", UserStatus.WITHDRAWN);
        Notification first = comment(owner, reader, 1L);
        Notification second = subscriber(owner, withdrawn);
        Notification system = new Notification(owner, null, null, NotificationType.NEW_SUBSCRIBER, null, null, null);
        em.persist(system);
        comment(other, reader, 2L);
        fx.flushAndClear();

        queryCounter.reset();
        Page<NotificationRow> page = repository.findMine(owner.getId(), PageRequest.of(0, 20));
        assertThat(queryCounter.count()).isEqualTo(2);

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(NotificationRow::id)
                .containsExactly(system.getId(), second.getId(), first.getId());
        NotificationRow row = page.getContent().get(2);
        assertThat(row.type()).isEqualTo(NotificationType.NEW_COMMENT);
        assertThat(row.actorId()).isEqualTo(reader.getId());
        assertThat(row.actorNickname()).isEqualTo("reader");
        assertThat(row.actorStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(row.actorProfileMediaKey()).isEqualTo("readerprofile000000000");
        assertThat(row.blogHandle()).isEqualTo("marco");
        assertThat(row.blogTitle()).isEqualTo("owner 블로그");
        assertThat(row.targetType()).isEqualTo(NotificationTargetType.COMMENT);
        assertThat(row.targetId()).isEqualTo(1L);
        assertThat(row.params()).containsEntry("postTitle", "첫 글").containsKey("postId");
        assertThat(row.readAt()).isNull();
        assertThat(row.createdAt()).isEqualTo(NOW);
        assertThat(page.getContent().get(1).actorStatus()).isEqualTo(UserStatus.WITHDRAWN);
        NotificationRow systemRow = page.getContent().getFirst();
        assertThat(systemRow.actorId()).isNull();
        assertThat(systemRow.blogHandle()).isNull();

        assertThat(repository.findMine(owner.getId(), PageRequest.of(1, 2)).getContent()).extracting(NotificationRow::id)
                .containsExactly(first.getId());
    }

    @Test
    void findOneOnlyMine() {
        Notification mine = comment(owner, reader, 1L);
        fx.flushAndClear();

        assertThat(repository.findMine(owner.getId(), mine.getId())).isPresent();
        assertThat(repository.findMine(other.getId(), mine.getId())).isEmpty();
        assertThat(repository.findMine(owner.getId(), mine.getId() + 999)).isEmpty();
    }

    @Test
    void markReadOnlyMineAndCountUnread() {
        Notification a = comment(owner, reader, 1L);
        Notification b = comment(owner, reader, 2L);
        Notification c = subscriber(owner, reader);
        Notification others = comment(other, reader, 3L);
        fx.flushAndClear();
        assertThat(repository.countUnread(owner.getId())).isEqualTo(3);

        // 다른 회원의 알림 id는 0행
        assertThat(repository.markRead(owner.getId(), List.of(others.getId()), NOW)).isZero();
        assertThat(repository.markRead(owner.getId(), List.of(a.getId()), NOW)).isEqualTo(1);
        // 이미 읽은 것은 그대로(세지 않음)
        assertThat(repository.markRead(owner.getId(), List.of(a.getId(), b.getId()), NOW.plusSeconds(60)))
                .isEqualTo(1);
        assertThat(repository.markRead(owner.getId(), List.of(), NOW)).isZero();
        em.clear();
        assertThat(em.find(Notification.class, a.getId()).getReadAt()).isEqualTo(NOW);
        assertThat(repository.countUnread(owner.getId())).isEqualTo(1);

        // ids 생략 → 안 읽은 것 전부
        assertThat(repository.markRead(owner.getId(), null, NOW)).isEqualTo(1);
        assertThat(repository.countUnread(owner.getId())).isZero();
        assertThat(repository.countUnread(other.getId())).isEqualTo(1);
        em.clear();
        assertThat(em.find(Notification.class, c.getId()).getReadAt()).isEqualTo(NOW);
    }

    @Test
    void subscriberNotificationWithinWindow() {
        subscriber(owner, reader);
        comment(owner, other, 1L);
        fx.flushAndClear();
        Instant created = NOW;

        assertThat(repository.existsSubscriberNotificationSince(owner.getId(), reader.getId(), blog.getId(),
                created.minusSeconds(1))).isTrue();
        assertThat(repository.existsSubscriberNotificationSince(owner.getId(), reader.getId(), blog.getId(),
                created.plusSeconds(1))).isFalse();
        // 다른 구독자, 다른 종류(NEW_COMMENT)는 해당하지 않는다
        assertThat(repository.existsSubscriberNotificationSince(owner.getId(), other.getId(), blog.getId(),
                created.minusSeconds(1))).isFalse();
        assertThat(repository.existsSubscriberNotificationSince(other.getId(), reader.getId(), blog.getId(),
                created.minusSeconds(1))).isFalse();
    }

    @Test
    void deleteOldAndByReceivers() {
        Notification old1 = comment(owner, reader, 1L);
        Notification old2 = comment(other, reader, 2L);
        clock.advance(Duration.ofDays(1));
        Notification recent = comment(owner, reader, 3L);
        Notification readers = comment(reader, owner, 4L);
        fx.flushAndClear();

        Instant cutoff = NOW.plus(Duration.ofHours(1));
        assertThat(repository.findIdsCreatedBefore(cutoff, 1)).containsExactly(old1.getId());
        List<Long> ids = repository.findIdsCreatedBefore(cutoff, 10);
        assertThat(ids).containsExactly(old1.getId(), old2.getId());
        assertThat(repository.deleteByIds(ids)).isEqualTo(2);
        assertThat(repository.deleteByIds(List.of())).isZero();
        assertThat(repository.findIdsCreatedBefore(cutoff, 10)).isEmpty();

        assertThat(repository.deleteByUserIds(List.of(owner.getId()))).isEqualTo(1);
        assertThat(repository.deleteByUserIds(List.of())).isZero();
        em.clear();
        assertThat(em.find(Notification.class, recent.getId())).isNull();
        assertThat(em.find(Notification.class, readers.getId())).isNotNull();
    }
}
