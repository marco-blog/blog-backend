package net.java21.blog.backend.user.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.auth.domain.PasswordResetToken;
import net.java21.blog.backend.auth.domain.RefreshToken;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.crypto.PersonalDataHasher;
import net.java21.blog.backend.guest.GuestProperties;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.media.repository.MediaQueryRepository;
import net.java21.blog.backend.media.repository.MediaRepository;
import net.java21.blog.backend.media.repository.PostMediaRepository;
import net.java21.blog.backend.media.service.MediaReferenceService;
import net.java21.blog.backend.media.storage.MediaStorage;
import net.java21.blog.backend.notification.domain.Notification;
import net.java21.blog.backend.notification.domain.NotificationTargetType;
import net.java21.blog.backend.notification.domain.NotificationType;
import net.java21.blog.backend.notification.repository.NotificationQueryRepository;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.PrivacyProperties;
import net.java21.blog.backend.user.domain.LoginHistory;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.LoginHistoryQueryRepository;
import net.java21.blog.backend.user.repository.PrivacyPurgeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 개인정보 파기 작업(T127, FR-138·139, research R26).
 * <ul>
 *   <li>{@code withdrawn_at < 지금 - 30일}인 탈퇴 회원: {@code email_hash} = HMAC("withdrawn:{id}"), {@code email_enc} = 같은 문자열의
 *       암호문(NOT NULL·UNIQUE 유지, tasks.md "구현 전 결정 사항" 10번), 닉네임 익명화, 소개·프로필 이미지 NULL. 한 번만 처리.</li>
 *   <li>90일 지난 로그인 기록 삭제, 만료된 재설정 토큰과 절대 만료가 지난 리프레시 토큰 삭제.</li>
 *   <li>004: 90일 지난 비회원 댓글·방명록의 IP만 비운다(T014, 001 FR-134).</li>
 *   <li>정해진 건수씩 나눠 처리하고 건수를 로그에 남긴다.</li>
 * </ul>
 */
@JpaRepositoryTest
@ExtendWith(OutputCaptureExtension.class)
@Import(PrivacyPurgeJobTest.Config.class)
class PrivacyPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    @Import({PrivacyPurgeRepository.class, LoginHistoryQueryRepository.class, MediaQueryRepository.class,
            NotificationQueryRepository.class})
    static class Config {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
            return new TransactionTemplate(transactionManager);
        }

        @Bean
        MediaReferenceService mediaReferenceService(MediaRepository mediaRepository,
                MediaQueryRepository mediaQueryRepository, PostMediaRepository postMediaRepository, MutableClock clock) {
            return new MediaReferenceService(mediaRepository, mediaQueryRepository, postMediaRepository,
                    Mockito.mock(MediaStorage.class), clock);
        }

        /** 배치 크기 2: 여러 번에 나눠 처리하는지 확인한다. */
        @Bean
        PrivacyPurgeJob privacyPurgeJob(PrivacyPurgeRepository repository, LoginHistoryQueryRepository loginHistory,
                PersonalDataHasher hasher, TransactionTemplate transactionTemplate, MutableClock clock,
                MediaReferenceService mediaReferences, NotificationQueryRepository notifications) {
            return new PrivacyPurgeJob(repository, loginHistory, hasher, transactionTemplate,
                    new PrivacyProperties(Duration.ofDays(30), Duration.ofDays(90)),
                    new JobsProperties("0 30 3 * * *", Duration.ofDays(30), 2), clock, mediaReferences,
                    notifications, new GuestProperties(5, 3, Duration.ofDays(90)));
        }
    }

    @Autowired
    private PrivacyPurgeJob job;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private User active;
    private int mediaSeq;

    @BeforeEach
    void setUp() {
        active = user("active@example.com", null);
    }

    private User user(String email, Instant withdrawnAt) {
        User user = new User(email, TestEntities.HASHER.hashEmail(email), "$2a$hash", "닉" + email.charAt(0), "ko",
                null, "2026-10-06", NOW.minus(Duration.ofDays(400)));
        TestEntities.with(user, "bio", "소개");
        if (withdrawnAt != null) {
            TestEntities.with(user, "status", UserStatus.WITHDRAWN);
            TestEntities.with(user, "withdrawnAt", withdrawnAt);
        }
        em.persist(user);
        Media profile = new Media(user, ("profile" + (++mediaSeq) + "0000000000000000000000").substring(0, 22),
                MediaPurpose.PROFILE, "p.png", "2026/10/p.png", "image/png", 10, 1, 1);
        TestEntities.with(profile, "status", MediaStatus.ATTACHED);
        em.persist(profile);
        user.changeProfileMedia(profile);
        return user;
    }

    private User reload(User user) {
        em.flush();
        em.clear();
        return em.find(User.class, user.getId());
    }

    @Test
    void purgesPersonalDataOfMembersWithdrawnMoreThanThirtyDaysAgo() {
        User old = user("old@example.com", NOW.minus(Duration.ofDays(30)).minusSeconds(1));
        User recent = user("recent@example.com", NOW.minus(Duration.ofDays(29)));
        em.flush();

        PrivacyPurgeJob.Result result = job.purge();

        assertThat(result.users()).isEqualTo(1);
        User purged = reload(old);
        String anonymous = "withdrawn:" + old.getId();
        assertThat(purged.getEmailHash()).isEqualTo(TestEntities.HASHER.hash(anonymous));
        assertThat(purged.getEmail()).isEqualTo(anonymous);
        assertThat(purged.getNickname()).isEqualTo(User.PURGED_NICKNAME);
        assertThat(purged.getBio()).isNull();
        assertThat(purged.getProfileMediaId()).isNull();
        // 이전 프로필 이미지는 아무도 쓰지 않으므로 정리 대상(FR-073), 아직 파기하지 않은 회원의 이미지는 그대로
        assertThat(jdbc.queryForObject("SELECT m.status FROM media m WHERE m.owner_id = ?", String.class, old.getId()))
                .isEqualTo("ORPHANED");
        assertThat(jdbc.queryForObject("SELECT m.status FROM media m WHERE m.owner_id = ?", String.class,
                recent.getId())).isEqualTo("ATTACHED");
        assertThat(purged.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
        byte[] raw = jdbc.queryForObject("SELECT email_enc FROM users WHERE id = ?", byte[].class, old.getId());
        assertThat(new String(raw, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("old@example.com")
                .doesNotContain(anonymous);

        User kept = reload(recent);
        assertThat(kept.getEmail()).isEqualTo("recent@example.com");
        assertThat(kept.getBio()).isEqualTo("소개");
        User stillActive = reload(active);
        assertThat(stillActive.getEmail()).isEqualTo("active@example.com");
        assertThat(stillActive.getNickname()).isEqualTo("닉a");
    }

    @Test
    void deletesNotificationsReceivedByPurgedMembers() {
        User old = user("old@example.com", NOW.minus(Duration.ofDays(31)));
        User recent = user("recent@example.com", NOW.minus(Duration.ofDays(1)));
        for (User receiver : List.of(old, old, recent, active)) {
            em.persist(new Notification(receiver, active, null, NotificationType.NEW_SUBSCRIBER,
                    NotificationTargetType.BLOG, 1L, Map.of("blogTitle", "블로그")));
        }
        // 파기되는 회원이 일으킨 알림은 받는 회원의 것이므로 남는다("탈퇴한 회원"으로 보인다)
        em.persist(new Notification(active, old, null, NotificationType.NEW_COMMENT, NotificationTargetType.COMMENT,
                2L, Map.of("postId", 3, "postTitle", "글")));
        em.flush();

        assertThat(job.purge().users()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class,
                old.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class,
                recent.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ?", Long.class,
                active.getId())).isEqualTo(2);
    }

    @Test
    void processesInBatchesAndOnlyOnce(CapturedOutput output) {
        Instant longAgo = NOW.minus(Duration.ofDays(60));
        List<User> withdrawn = List.of(user("a1@example.com", longAgo), user("a2@example.com", longAgo),
                user("a3@example.com", longAgo));
        em.flush();

        assertThat(job.purge().users()).isEqualTo(3);
        assertThat(withdrawn).allSatisfy(u -> assertThat(reload(u).getNickname()).isEqualTo(User.PURGED_NICKNAME));
        assertThat(output).contains("Privacy purge finished: users=3");

        assertThat(job.purge().users()).isZero();
    }

    @Test
    void deletesLoginHistoryOlderThanNinetyDays() {
        Instant cutoff = NOW.minus(Duration.ofDays(90));
        em.persist(new LoginHistory(active, true, "10.0.0.1", null, cutoff.minusSeconds(1)));
        em.persist(new LoginHistory(null, false, "10.0.0.2", null, cutoff.minus(Duration.ofDays(5))));
        em.persist(new LoginHistory(active, false, "10.0.0.3", null, cutoff.minus(Duration.ofDays(1))));
        em.persist(new LoginHistory(active, true, "10.0.0.4", null, cutoff.plusSeconds(1)));
        em.flush();

        assertThat(job.purge().loginHistory()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM login_history", Long.class)).isEqualTo(1);
    }

    @Test
    void deletesExpiredResetTokensAndFullyExpiredRefreshTokens() {
        em.persist(PasswordResetToken.issue(active, "1".repeat(64), NOW.minus(Duration.ofMinutes(31))));
        em.persist(PasswordResetToken.issue(active, "2".repeat(64), NOW.minus(Duration.ofMinutes(10))));
        // 절대 만료(7일)가 지난 계열만 지운다. 유휴 만료만 지난 토큰은 재사용 감지를 위해 계열이 끝날 때까지 남긴다.
        em.persist(RefreshToken.first(active, "family-old", "3".repeat(64), NOW.minus(Duration.ofDays(8)),
                Duration.ofHours(4), Duration.ofDays(7)));
        em.persist(RefreshToken.first(active, "family-idle", "4".repeat(64), NOW.minus(Duration.ofDays(1)),
                Duration.ofHours(4), Duration.ofDays(7)));
        em.flush();

        PrivacyPurgeJob.Result result = job.purge();

        assertThat(result.resetTokens()).isEqualTo(1);
        assertThat(result.refreshTokens()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT token_hash FROM password_reset_tokens", String.class))
                .containsExactly("2".repeat(64));
        assertThat(jdbc.queryForList("SELECT family_id FROM refresh_tokens", String.class))
                .containsExactly("family-idle");
    }

    @Test
    void clearsOnlyGuestIpsOlderThanRetentionInBatches(CapturedOutput output) {
        Blog blog = new Blog(active, "active", "블로그");
        em.persist(blog);
        Post post = new Post(blog, "글");
        post.publish("글", "본문", "<p>본문</p>", "본문", "요약", null, PostVisibility.PUBLIC, true, NOW);
        em.persist(post);
        Comment member = new Comment(post, active, null, "회원 댓글");
        em.persist(member);
        List<Comment> oldComments = List.of(guestComment(post, "1"), guestComment(post, "2"), guestComment(post, "3"));
        Comment fresh = guestComment(post, "4");
        GuestbookEntry oldEntry = GuestbookEntry.byGuest(blog, "손님", "$2a$guest", "198.51.100.9", "안녕", false);
        em.persist(oldEntry);
        em.flush();
        java.sql.Timestamp old = java.sql.Timestamp.from(NOW.minus(Duration.ofDays(90)).minusSeconds(1));
        for (Comment c : oldComments) {
            jdbc.update("UPDATE comments SET created_at = ? WHERE id = ?", old, c.getId());
        }
        jdbc.update("UPDATE comments SET created_at = ? WHERE id = ?", old, member.getId());
        jdbc.update("UPDATE comments SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.minus(Duration.ofDays(89))), fresh.getId());
        jdbc.update("UPDATE guestbook_entries SET created_at = ? WHERE id = ?", old, oldEntry.getId());
        em.clear();

        PrivacyPurgeJob.Result result = job.purge();

        assertThat(result.guestIps()).isEqualTo(4);
        assertThat(jdbc.queryForList("SELECT id FROM comments WHERE guest_ip_enc IS NOT NULL", Long.class))
                .containsExactly(fresh.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM guestbook_entries WHERE guest_ip_enc IS NULL",
                Long.class)).isEqualTo(1);
        Comment cleared = em.find(Comment.class, oldComments.get(0).getId());
        assertThat(cleared.getGuestName()).isEqualTo("손님1");
        assertThat(cleared.getGuestPasswordHash()).isEqualTo("$2a$guest");
        assertThat(cleared.getContent()).isEqualTo("비회원 1");
        assertThat(em.find(Comment.class, member.getId()).getContent()).isEqualTo("회원 댓글");
        assertThat(output).contains("guestIps=4");
        assertThat(job.purge().guestIps()).isZero();
    }

    private Comment guestComment(Post post, String n) {
        Comment c = Comment.byGuest(post, null, "손님" + n, "$2a$guest", "203.0.113." + n, "비회원 " + n, false);
        em.persist(c);
        return c;
    }

    @Test
    void scheduledRunUsesConfiguredCron() throws NoSuchMethodException {
        var scheduled = PrivacyPurgeJob.class.getMethod("run")
                .getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${blog.jobs.privacy-purge-cron:0 0 4 * * *}");
        job.run();
    }
}
