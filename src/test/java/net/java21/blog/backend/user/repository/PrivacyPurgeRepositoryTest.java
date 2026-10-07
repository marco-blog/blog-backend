package net.java21.blog.backend.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 비회원 IP 파기 조회·비우기(T014, 001 FR-134): 기간 지난 비회원 행만, 오래된 순으로 건수 제한, 회원 행은 비우지 않는다.
 * 005 T013: 처리 후 기간이 지난 권리 침해 연락 이메일, 기간이 지난 트랙백 송신 IP만 비운다.
 */
@JpaRepositoryTest
class PrivacyPurgeRepositoryTest {

    private static final Instant CUTOFF = Instant.parse("2026-07-01T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private JdbcTemplate jdbc;

    private PrivacyPurgeRepository repository;
    private Post post;
    private Blog blog;
    private User owner;

    @BeforeEach
    void setUp() {
        repository = new PrivacyPurgeRepository(queryFactory);
        JpaFixtures fx = new JpaFixtures(em);
        owner = fx.user("marco");
        blog = fx.blog(owner, "marco");
        post = fx.published(blog, "글", null, 0);
    }

    @Test
    void findsOldGuestCommentsWithIpOldestFirstAndClearsOnlyGuestRows() {
        Comment a = guest("a", "203.0.113.1", CUTOFF.minusSeconds(10));
        Comment b = guest("b", "203.0.113.2", CUTOFF.minusSeconds(5));
        guest("noip", null, CUTOFF.minusSeconds(5));
        guest("new", "203.0.113.3", CUTOFF.plusSeconds(5));
        Comment member = new Comment(post, owner, null, "회원");
        em.persist(member);
        em.flush();
        age("comments", member.getId(), CUTOFF.minusSeconds(100));

        assertThat(repository.findGuestIpCommentIds(CUTOFF, 10)).containsExactly(a.getId(), b.getId());
        assertThat(repository.findGuestIpCommentIds(CUTOFF, 1)).containsExactly(a.getId());

        assertThat(repository.clearCommentGuestIp(List.of(a.getId(), member.getId()))).isEqualTo(1);
        assertThat(repository.findGuestIpCommentIds(CUTOFF, 10)).containsExactly(b.getId());
        assertThat(repository.clearCommentGuestIp(List.of())).isZero();
    }

    @Test
    void findsAndClearsOldGuestbookIps() {
        GuestbookEntry old = GuestbookEntry.byGuest(blog, "손님", "$2a$x", "198.51.100.1", "안녕", false);
        em.persist(old);
        GuestbookEntry member = new GuestbookEntry(blog, owner, null, "회원", false);
        em.persist(member);
        em.flush();
        age("guestbook_entries", old.getId(), CUTOFF.minusSeconds(1));
        age("guestbook_entries", member.getId(), CUTOFF.minusSeconds(1));

        assertThat(repository.findGuestIpGuestbookIds(CUTOFF, 10)).containsExactly(old.getId());
        assertThat(repository.clearGuestbookGuestIp(List.of(old.getId(), member.getId()))).isEqualTo(1);
        assertThat(repository.findGuestIpGuestbookIds(CUTOFF, 10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT guest_name FROM guestbook_entries WHERE id = ?", String.class,
                old.getId())).isEqualTo("손님");
        assertThat(repository.clearGuestbookGuestIp(List.of())).isZero();
    }

    @Test
    void findsAndClearsHandledRightsRequestContactsOnly() {
        Report handledOld = rights("old@example.com");
        Report handledNew = rights("new@example.com");
        Report pending = rights("pending@example.com");
        Report member = Report.member(owner, ReportTargetType.POST, post.getId(), owner, blog, ReportReason.SPAM, null);
        em.persist(member);
        em.flush();
        handle(handledOld, CUTOFF.minusSeconds(10));
        handle(handledNew, CUTOFF.plusSeconds(10));
        jdbc.update("UPDATE reports SET created_at = ? WHERE id = ?", Timestamp.from(CUTOFF.minusSeconds(999)),
                pending.getId());

        assertThat(repository.findExpiredRightsContactIds(CUTOFF, 10)).containsExactly(handledOld.getId());
        assertThat(repository.clearRightsContact(List.of(handledOld.getId(), member.getId()))).isEqualTo(1);
        assertThat(repository.findExpiredRightsContactIds(CUTOFF, 10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT rights_basis FROM reports WHERE id = ?", String.class,
                handledOld.getId())).isEqualTo("근거");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reports WHERE contact_email_enc IS NULL", Long.class))
                .isEqualTo(2);
        assertThat(repository.clearRightsContact(List.of())).isZero();
    }

    @Test
    void findsAndClearsOldTrackbackIps() {
        Trackback old = trackback("1", "203.0.113.1");
        Trackback noIp = trackback("2", null);
        Trackback fresh = trackback("3", "203.0.113.3");
        age("trackbacks", old.getId(), CUTOFF.minusSeconds(1));
        age("trackbacks", noIp.getId(), CUTOFF.minusSeconds(1));
        age("trackbacks", fresh.getId(), CUTOFF.plusSeconds(1));

        assertThat(repository.findExpiredTrackbackIpIds(CUTOFF, 10)).containsExactly(old.getId());
        assertThat(repository.clearTrackbackIp(List.of(old.getId()))).isEqualTo(1);
        assertThat(repository.findExpiredTrackbackIpIds(CUTOFF, 10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT title FROM trackbacks WHERE id = ?", String.class, old.getId()))
                .isEqualTo("제목1");
        assertThat(repository.clearTrackbackIp(List.of())).isZero();
    }

    private Report rights(String email) {
        Report report = Report.rightsRequest("https://example.com/" + email, ReportReason.COPYRIGHT, "근거", email);
        em.persist(report);
        em.flush();
        return report;
    }

    private void handle(Report report, Instant at) {
        jdbc.update("UPDATE reports SET status = 'DISMISSED', handled_at = ? WHERE id = ?", Timestamp.from(at),
                report.getId());
    }

    private Trackback trackback(String n, String ip) {
        Trackback trackback = new Trackback(post, null, "https://ext.example/" + n,
                String.valueOf(n.charAt(0)).repeat(64), "제목" + n, null, null, ip);
        em.persist(trackback);
        em.flush();
        return trackback;
    }

    private Comment guest(String name, String ip, Instant createdAt) {
        Comment c = Comment.byGuest(post, null, name, "$2a$x", ip, "내용", false);
        em.persist(c);
        em.flush();
        age("comments", c.getId(), createdAt);
        return c;
    }

    private void age(String table, Long id, Instant createdAt) {
        jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), id);
    }
}
