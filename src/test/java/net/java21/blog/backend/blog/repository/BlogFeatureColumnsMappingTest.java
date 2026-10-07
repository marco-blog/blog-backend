package net.java21.blog.backend.blog.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.block.domain.BlogBlock;
import net.java21.blog.backend.block.domain.BlogBlockId;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.export.domain.BlogExport;
import net.java21.blog.backend.export.domain.ExportStatus;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.sidebar.domain.BlogSidebarItem;
import net.java21.blog.backend.sidebar.domain.BlogSidebarItemId;
import net.java21.blog.backend.sidebar.domain.SidebarItemType;
import net.java21.blog.backend.stats.domain.BlogDailyVisit;
import net.java21.blog.backend.stats.domain.BlogDailyVisitId;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 004 매핑(T005): 글의 보호·예약·공지 컬럼, 블로그의 방명록·비회원·방문자 컬럼, 비회원·비밀 댓글, 방명록, 사이드바·일별 방문·차단(복합 키),
 * 내보내기를 저장 후 다시 읽고, {@code @Check}가 H2에서도 막는지 확인한다. 실제 MySQL 스키마와의 일치는
 * {@code EntitySchemaValidationTest}가 확인한다.
 */
@JpaRepositoryTest
class BlogFeatureColumnsMappingTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private JpaFixtures fx;
    private User owner;
    private Blog blog;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        owner = fx.user("marco");
        blog = fx.blog(owner, "marco");
    }

    @Test
    void postProtectionScheduleAndNotice() {
        Post post = fx.published(blog, "글", null, 0);
        fx.flushAndClear();
        Post fresh = em.find(Post.class, post.getId());
        assertThat(fresh.getPasswordHash()).isNull();
        assertThat(fresh.getScheduledAt()).isNull();
        assertThat(fresh.isNotice()).isFalse();

        fresh.changeNotice(true);
        fx.flushAndClear();
        jdbc.update("UPDATE posts SET scheduled_at = ? WHERE id = ?", java.sql.Timestamp.from(NOW), post.getId());

        Post found = em.find(Post.class, post.getId());
        assertThat(found.isNotice()).isTrue();
        assertThat(found.getScheduledAt()).isEqualTo(NOW);
        assertThat(found.getVisibility()).isEqualTo(PostVisibility.PUBLIC);
        // PROTECTED 값은 US3(T090)에서 enum에 더한다. 그 전에는 H2의 enum 열이 값을 막으므로 해시가 있는 글은 만들 수 없다.
    }

    @Test
    void protectedPasswordCheckHoldsInH2() {
        Post post = fx.published(blog, "글", null, 0);
        fx.flushAndClear();
        assertThatThrownBy(() -> jdbc.update("UPDATE posts SET password_hash = 'x' WHERE id = ?", post.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE posts SET visibility = 'PROTECTED' WHERE id = ?", post.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newBlogGuestDefaultsAndChanges() {
        fx.flushAndClear();
        Blog fresh = em.find(Blog.class, blog.getId());
        assertThat(fresh.isGuestbookEnabled()).isTrue();
        assertThat(fresh.isGuestWriteEnabled()).isFalse();
        assertThat(fresh.getTotalVisitors()).isZero();
        assertThat(fresh.isOwnedBy(owner.getId())).isTrue();
        assertThat(fresh.isOwnedBy(null)).isFalse();

        fresh.changeGuestSettings(false, true);
        fx.flushAndClear();
        jdbc.update("UPDATE blogs SET total_visitors = 42 WHERE id = ?", blog.getId());

        Blog found = em.find(Blog.class, blog.getId());
        assertThat(found.isGuestbookEnabled()).isFalse();
        assertThat(found.isGuestWriteEnabled()).isTrue();
        assertThat(found.getTotalVisitors()).isEqualTo(42);
    }

    @Test
    void guestAndSecretCommentsWithEncryptedIp() {
        Post post = fx.published(blog, "글", null, 0);
        Comment member = new Comment(post, owner, null, "회원 댓글");
        member.changeSecret(true);
        em.persist(member);
        Comment guest = Comment.byGuest(post, member, "손님", "$2a$guest", "203.0.113.7", "비회원 답글", false);
        em.persist(guest);
        fx.flushAndClear();

        byte[] stored = jdbc.queryForObject("SELECT guest_ip_enc FROM comments WHERE id = ?", byte[].class,
                guest.getId());
        assertThat(stored).isNotNull();
        assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("203.0.113.7");

        Comment found = em.find(Comment.class, guest.getId());
        assertThat(found.getUser()).isNull();
        assertThat(found.isGuest()).isTrue();
        assertThat(found.getGuestName()).isEqualTo("손님");
        assertThat(found.getGuestPasswordHash()).isEqualTo("$2a$guest");
        assertThat(found.getGuestIp()).isEqualTo("203.0.113.7");
        assertThat(found.isSecret()).isFalse();
        assertThat(found.getParent().getId()).isEqualTo(member.getId());
        Comment foundMember = em.find(Comment.class, member.getId());
        assertThat(foundMember.isSecret()).isTrue();
        assertThat(foundMember.isGuest()).isFalse();
        assertThat(foundMember.getGuestIp()).isNull();
    }

    @Test
    void commentAuthorCheckHoldsInH2() {
        Post post = fx.published(blog, "글", null, 0);
        Comment member = new Comment(post, owner, null, "회원");
        em.persist(member);
        Comment guest = Comment.byGuest(post, null, "손님", "$2a$guest", null, "비회원", false);
        em.persist(guest);
        fx.flushAndClear();

        assertThatThrownBy(() -> jdbc.update("UPDATE comments SET guest_name = 'x' WHERE id = ?", member.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE comments SET guest_name = NULL WHERE id = ?", guest.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE comments SET guest_password_hash = NULL WHERE id = ?",
                guest.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void guestbookEntriesRoundTrip() {
        User visitor = fx.user("visitor");
        GuestbookEntry entry = new GuestbookEntry(blog, visitor, null, "안녕하세요", true);
        em.persist(entry);
        GuestbookEntry reply = new GuestbookEntry(blog, owner, entry, "반가워요", false);
        em.persist(reply);
        GuestbookEntry guest = GuestbookEntry.byGuest(blog, "손님", "$2a$guest", "198.51.100.2", "들렀다 가요", false);
        em.persist(guest);
        fx.flushAndClear();

        GuestbookEntry found = em.find(GuestbookEntry.class, entry.getId());
        assertThat(found.getContent()).isEqualTo("안녕하세요");
        assertThat(found.isSecret()).isTrue();
        assertThat(found.getStatus()).isEqualTo(GuestbookStatus.ACTIVE);
        assertThat(found.getUserId()).isEqualTo(visitor.getId());
        assertThat(found.isOwnedBy(visitor.getId())).isTrue();
        assertThat(found.getCreatedAt()).isNotNull();
        GuestbookEntry foundReply = em.find(GuestbookEntry.class, reply.getId());
        assertThat(foundReply.isReply()).isTrue();
        assertThat(foundReply.getParent().getId()).isEqualTo(entry.getId());
        GuestbookEntry foundGuest = em.find(GuestbookEntry.class, guest.getId());
        assertThat(foundGuest.isGuest()).isTrue();
        assertThat(foundGuest.getGuestIp()).isEqualTo("198.51.100.2");
        assertThat(foundGuest.getGuestName()).isEqualTo("손님");

        foundGuest.edit("고침", true);
        foundGuest.markDeleted();
        fx.flushAndClear();
        GuestbookEntry deleted = em.find(GuestbookEntry.class, guest.getId());
        assertThat(deleted.isDeleted()).isTrue();
        assertThat(deleted.isSecret()).isTrue();

        assertThatThrownBy(() -> jdbc.update("UPDATE guestbook_entries SET guest_name = NULL WHERE id = ?",
                guest.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE guestbook_entries SET guest_name = 'x' WHERE id = ?",
                entry.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void compositeKeyTablesRoundTrip() {
        User blocked = fx.user("troll");
        em.persist(new BlogSidebarItem(blog, SidebarItemType.TAGS, false, 3));
        em.persist(new BlogDailyVisit(blog, LocalDate.of(2026, 10, 7), 12));
        em.persist(new BlogBlock(blog, blocked, NOW));
        fx.flushAndClear();

        BlogSidebarItem item = em.find(BlogSidebarItem.class, new BlogSidebarItemId(blog.getId(), SidebarItemType.TAGS));
        assertThat(item.getType()).isEqualTo(SidebarItemType.TAGS);
        assertThat(item.isEnabled()).isFalse();
        assertThat(item.getSortOrder()).isEqualTo(3);
        BlogDailyVisit visit = em.find(BlogDailyVisit.class, new BlogDailyVisitId(blog.getId(), LocalDate.of(2026, 10, 7)));
        assertThat(visit.getVisitors()).isEqualTo(12);
        assertThat(visit.getVisitDate()).isEqualTo(LocalDate.of(2026, 10, 7));
        BlogBlock block = em.find(BlogBlock.class, new BlogBlockId(blog.getId(), blocked.getId()));
        assertThat(block.getCreatedAt()).isEqualTo(NOW);
        assertThat(block.getBlockedUser().getId()).isEqualTo(blocked.getId());
        assertThat(block.getBlog().getId()).isEqualTo(blog.getId());
        assertThat(jdbc.queryForObject("SELECT item_type FROM blog_sidebar_items WHERE blog_id = ?", String.class,
                blog.getId())).isEqualTo("TAGS");
    }

    @Test
    void exportLifecycle() {
        BlogExport ready = new BlogExport(blog, owner);
        em.persist(ready);
        BlogExport failed = new BlogExport(blog, owner);
        em.persist(failed);
        fx.flushAndClear();
        assertThat(em.find(BlogExport.class, ready.getId()).getStatus()).isEqualTo(ExportStatus.PENDING);

        BlogExport r = em.find(BlogExport.class, ready.getId());
        r.markReady("2026/10/marco.zip", 2048, NOW, Duration.ofDays(7));
        em.find(BlogExport.class, failed.getId()).markFailed("IO_ERROR");
        fx.flushAndClear();

        BlogExport found = em.find(BlogExport.class, ready.getId());
        assertThat(found.getStatus()).isEqualTo(ExportStatus.READY);
        assertThat(found.getFilePath()).isEqualTo("2026/10/marco.zip");
        assertThat(found.getFileSize()).isEqualTo(2048L);
        assertThat(found.getCompletedAt()).isEqualTo(NOW);
        assertThat(found.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(found.isDownloadable(NOW.plusSeconds(1))).isTrue();
        assertThat(found.isDownloadable(NOW.plus(Duration.ofDays(8)))).isFalse();
        assertThat(found.getRequestedBy().getId()).isEqualTo(owner.getId());
        assertThat(found.getCreatedAt()).isNotNull();
        found.markExpired();
        fx.flushAndClear();
        assertThat(em.find(BlogExport.class, ready.getId()).getStatus()).isEqualTo(ExportStatus.EXPIRED);
        assertThat(em.find(BlogExport.class, ready.getId()).isDownloadable(NOW)).isFalse();

        BlogExport foundFailed = em.find(BlogExport.class, failed.getId());
        assertThat(foundFailed.getStatus()).isEqualTo(ExportStatus.FAILED);
        assertThat(foundFailed.getErrorCode()).isEqualTo("IO_ERROR");
        assertThat(foundFailed.getFilePath()).isNull();
    }
}
