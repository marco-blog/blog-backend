package net.java21.blog.backend.report.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.report.domain.Report;
import net.java21.blog.backend.report.domain.ReportChannel;
import net.java21.blog.backend.report.domain.ReportReason;
import net.java21.blog.backend.report.domain.ReportStatus;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.spam.domain.BannedWord;
import net.java21.blog.backend.spam.domain.BannedWordAction;
import net.java21.blog.backend.spam.domain.BannedWordScope;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.trackback.domain.PingErrorCode;
import net.java21.blog.backend.trackback.domain.PingStatus;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.domain.TrackbackPingLog;
import net.java21.blog.backend.trackback.domain.TrackbackStatus;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 005 T007: 신고·트랙백·금칙어 엔티티와 숨김 컬럼 매핑, H2에서도 CHECK·UNIQUE 위반. */
@JpaRepositoryTest
class ModerationColumnsMappingTest {

    private static final String HASH = "a".repeat(64);

    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private JpaFixtures fx;
    private User owner;
    private User reporter;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        owner = fx.user("owner");
        reporter = fx.user("reporter");
        blog = fx.blog(owner, "owner");
        post = fx.published(blog, "글", null, 0);
    }

    @Test
    void memberReportIsSavedAndReadBack() {
        Report report = Report.member(reporter, ReportTargetType.POST, post.getId(), owner, blog, ReportReason.SPAM,
                "광고");
        em.persist(report);
        fx.flushAndClear();

        Report found = em.find(Report.class, report.getId());
        assertThat(found.getChannel()).isEqualTo(ReportChannel.MEMBER);
        assertThat(found.getStatus()).isEqualTo(ReportStatus.PENDING);
        assertThat(found.getReporter().getId()).isEqualTo(reporter.getId());
        assertThat(found.getTargetUser().getId()).isEqualTo(owner.getId());
        assertThat(found.getTargetBlog().getId()).isEqualTo(blog.getId());
        assertThat(found.getDetail()).isEqualTo("광고");
        assertThat(found.hasTarget()).isTrue();
    }

    @Test
    void rightsRequestEncryptsContactEmailAndAllowsNullTarget() {
        Report first = Report.rightsRequest("https://example.com/x", ReportReason.COPYRIGHT, "내 글", "me@example.com");
        Report second = Report.rightsRequest("https://example.com/y", ReportReason.PRIVACY, "내 사진", "me@example.com");
        em.persist(first);
        em.persist(second);
        fx.flushAndClear();

        Report found = em.find(Report.class, first.getId());
        assertThat(found.getReporter()).isNull();
        assertThat(found.hasTarget()).isFalse();
        assertThat(found.getContactEmail()).isEqualTo("me@example.com");
        String stored = jdbc.queryForObject("SELECT contact_email_enc FROM reports WHERE id = ?", String.class,
                first.getId());
        assertThat(stored).isNotBlank().doesNotContain("example.com");
    }

    @Test
    void channelCheckIsEnforced() {
        Report memberWithoutReporter = Report.member(null, ReportTargetType.POST, post.getId(), owner, blog,
                ReportReason.SPAM, null);
        assertThatThrownBy(() -> {
            em.persist(memberWithoutReporter);
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rightsRequestWithReporterViolatesCheck() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO reports (channel, reporter_id, target_url, reason, status, "
                + "created_at, updated_at) VALUES ('RIGHTS_REQUEST', ?, 'https://x', 'OTHER', 'PENDING', "
                + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", reporter.getId())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void sameMemberCannotReportSameTargetTwice() {
        em.persist(Report.member(reporter, ReportTargetType.POST, post.getId(), owner, blog, ReportReason.SPAM, null));
        em.flush();
        assertThatThrownBy(() -> {
            em.persist(Report.member(reporter, ReportTargetType.POST, post.getId(), owner, blog, ReportReason.ABUSE,
                    null));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void trackbackAndPingLogAreSavedAndReadBack() {
        Post source = fx.published(blog, "보낸 글", null, 1);
        Trackback trackback = new Trackback(post, source, "https://ext.example/p/1", HASH, "제목", "요약", "외부 블로그",
                "203.0.113.5");
        em.persist(trackback);
        TrackbackPingLog log = new TrackbackPingLog(post, "https://ext.example/tb");
        em.persist(log);
        fx.flushAndClear();

        Trackback found = em.find(Trackback.class, trackback.getId());
        assertThat(found.getStatus()).isEqualTo(TrackbackStatus.ACTIVE);
        assertThat(found.getSourcePost().getId()).isEqualTo(source.getId());
        assertThat(found.getSenderIp()).isEqualTo("203.0.113.5");
        assertThat(jdbc.queryForObject("SELECT sender_ip_enc FROM trackbacks WHERE id = ?", String.class,
                trackback.getId())).doesNotContain("203.0.113.5");
        assertThat(found.hide()).isTrue();
        assertThat(found.isHidden()).isTrue();
        assertThat(found.unhide()).isTrue();
        found.markDeleted();
        assertThat(found.getStatus()).isEqualTo(TrackbackStatus.DELETED);

        TrackbackPingLog foundLog = em.find(TrackbackPingLog.class, log.getId());
        assertThat(foundLog.getStatus()).isEqualTo(PingStatus.PENDING);
        foundLog.fail(PingErrorCode.TIMEOUT, "timeout", JpaFixtures.T0);
        fx.flushAndClear();
        TrackbackPingLog failed = em.find(TrackbackPingLog.class, log.getId());
        assertThat(failed.getStatus()).isEqualTo(PingStatus.FAILED);
        assertThat(failed.getErrorCode()).isEqualTo(PingErrorCode.TIMEOUT);
        assertThat(failed.getAttemptedAt()).isEqualTo(JpaFixtures.T0);
    }

    @Test
    void externalTrackbackHasNoSourcePostAndDuplicateUrlIsRejected() {
        em.persist(new Trackback(post, null, "https://ext.example/p/1", HASH, "제목", null, null, null));
        em.flush();
        assertThatThrownBy(() -> {
            em.persist(new Trackback(post, null, "https://ext.example/p/1", HASH, "제목", null, null, null));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void bannedWordIsUnique() {
        BannedWord word = new BannedWord(owner, "광고", BannedWordScope.ALL, BannedWordAction.REJECT);
        em.persist(word);
        fx.flushAndClear();
        BannedWord found = em.find(BannedWord.class, word.getId());
        assertThat(found.getScope()).isEqualTo(BannedWordScope.ALL);
        assertThat(found.getAction()).isEqualTo(BannedWordAction.REJECT);
        assertThat(found.getCreatedBy().getId()).isEqualTo(owner.getId());

        assertThatThrownBy(() -> {
            em.persist(new BannedWord(owner, "광고", BannedWordScope.NAME, BannedWordAction.MASK));
            em.flush();
        }).isInstanceOf(RuntimeException.class);
    }

    @Test
    void hiddenStatusesAndTrackbackFlagAreMapped() {
        Comment comment = new Comment(post, reporter, null, "댓글");
        em.persist(comment);
        GuestbookEntry entry = new GuestbookEntry(blog, reporter, null, "방명록", false);
        em.persist(entry);
        fx.flushAndClear();

        Post foundPost = em.find(Post.class, post.getId());
        assertThat(foundPost.hide()).isTrue();
        assertThat(foundPost.hide()).isFalse();
        em.find(Comment.class, comment.getId()).hide();
        em.find(GuestbookEntry.class, entry.getId()).hide();
        em.find(Blog.class, blog.getId()).changeTrackbackEnabled(false);
        fx.flushAndClear();

        Post hidden = em.find(Post.class, post.getId());
        assertThat(hidden.getStatus()).isEqualTo(PostStatus.HIDDEN);
        assertThat(hidden.getStatusBeforeHidden()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(em.find(Comment.class, comment.getId()).getStatus()).isEqualTo(CommentStatus.HIDDEN);
        assertThat(em.find(GuestbookEntry.class, entry.getId()).getStatus()).isEqualTo(GuestbookStatus.HIDDEN);
        assertThat(em.find(Blog.class, blog.getId()).isTrackbackEnabled()).isFalse();
        assertThat(fx.blog(owner, "second").isTrackbackEnabled()).isTrue();

        assertThat(hidden.unhide()).isTrue();
        fx.flushAndClear();
        Post restored = em.find(Post.class, post.getId());
        assertThat(restored.getStatus()).isEqualTo(PostStatus.PUBLISHED);
        assertThat(restored.getStatusBeforeHidden()).isNull();
    }
}
