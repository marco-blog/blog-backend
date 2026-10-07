package net.java21.blog.backend.guestbook.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.media.domain.MediaPurpose;
import net.java21.blog.backend.media.domain.MediaStatus;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 방명록 조회(T033): 최상위 글 페이지(ACTIVE와 답글이 남은 삭제 자리, 최신순, 전체 수), 작성자 LEFT JOIN(비회원 포함, 프로필 이미지),
 * 답글 한 번에(작성순), 글 수와 무관한 쿼리 수, 대시보드용 최근 수·최근 글.
 */
@JpaRepositoryTest
class GuestbookQueryRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private QueryCounter queryCounter;

    private GuestbookQueryRepository repository;
    private JpaFixtures fx;
    private Blog blog;
    private User owner;
    private User visitor;

    @BeforeEach
    void setUp() {
        repository = new GuestbookQueryRepository(queryFactory);
        fx = new JpaFixtures(em);
        owner = fx.user("marco");
        blog = fx.blog(owner, "marco");
        visitor = fx.user("visitor");
        Media profile = new Media(visitor, "profileVisitor00000000", MediaPurpose.PROFILE, "p.png", "2026/10/p.png",
                "image/png", 10, 1, 1);
        TestEntities.with(profile, "status", MediaStatus.ATTACHED);
        em.persist(profile);
        visitor.changeProfileMedia(profile);
    }

    @Test
    void pageOfTopLevelEntriesNewestFirstWithAuthorsAndRepliesInFixedQueries() {
        GuestbookEntry first = entry(new GuestbookEntry(blog, visitor, null, "첫 글", false), 1);
        GuestbookEntry guest = entry(GuestbookEntry.byGuest(blog, "손님", "$2a$x", "203.0.113.1", "비회원 글", true), 2);
        GuestbookEntry deletedWithReply = entry(new GuestbookEntry(blog, visitor, null, "지운 글", false), 3);
        entry(new GuestbookEntry(blog, visitor, null, "답글 없이 지운 글", false), 4).markDeleted();
        GuestbookEntry latest = entry(new GuestbookEntry(blog, visitor, null, "최신 글", false), 5);
        GuestbookEntry reply2 = entry(new GuestbookEntry(blog, owner, first, "두 번째 답글", false), 7);
        GuestbookEntry reply1 = entry(new GuestbookEntry(blog, owner, first, "첫 답글", false), 6);
        entry(new GuestbookEntry(blog, owner, deletedWithReply, "남은 답글", false), 8);
        deletedWithReply.markDeleted();
        Blog other = fx.blog(fx.user("polo"), "polo");
        entry(new GuestbookEntry(other, visitor, null, "다른 블로그", false), 9);
        fx.flushAndClear();

        queryCounter.reset();
        Page<GuestbookRow> page = repository.findPage(blog.getId(), PageRequest.of(0, 3));
        List<GuestbookRow> replies = repository.findReplies(page.getContent().stream().map(GuestbookRow::id).toList());

        assertThat(queryCounter.count()).isEqualTo(3);
        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.getContent()).extracting(GuestbookRow::id)
                .containsExactly(latest.getId(), deletedWithReply.getId(), guest.getId());
        assertThat(page.getContent().get(1).deleted()).isTrue();
        GuestbookRow guestRow = page.getContent().get(2);
        assertThat(guestRow.userId()).isNull();
        assertThat(guestRow.guestName()).isEqualTo("손님");
        assertThat(guestRow.secret()).isTrue();
        assertThat(page.getContent().get(0).profileMediaKey()).isEqualTo("profileVisitor00000000");
        assertThat(replies).extracting(GuestbookRow::content).containsExactly("남은 답글");

        queryCounter.reset();
        List<GuestbookRow> firstReplies = repository.findReplies(List.of(first.getId(), latest.getId()));
        assertThat(queryCounter.count()).isEqualTo(1);
        assertThat(firstReplies).extracting(GuestbookRow::id).containsExactly(reply1.getId(), reply2.getId());
        assertThat(firstReplies.get(0).parentId()).isEqualTo(first.getId());
        assertThat(firstReplies.get(0).nickname()).isEqualTo("marco");

        queryCounter.reset();
        assertThat(repository.findReplies(List.of())).isEmpty();
        assertThat(queryCounter.count()).isZero();
    }

    @Test
    void dashboardCountsAndRecentTopLevelEntries() {
        entry(new GuestbookEntry(blog, visitor, null, "오래된 글", false), -60 * 24 * 8);
        GuestbookEntry recent = entry(new GuestbookEntry(blog, visitor, null, "최근 글", true), 0);
        GuestbookEntry newer = entry(GuestbookEntry.byGuest(blog, "손님", "$2a$x", null, "더 최근", false), 1);
        entry(new GuestbookEntry(blog, owner, recent, "답글", true), 2);
        fx.flushAndClear();

        queryCounter.reset();
        assertThat(repository.countRecent(blog.getId(), T0.minusSeconds(60 * 60 * 24 * 7))).isEqualTo(2);
        List<GuestbookRow> rows = repository.findRecent(blog.getId(), 2);
        assertThat(queryCounter.count()).isEqualTo(2);
        assertThat(rows).extracting(GuestbookRow::id).containsExactly(newer.getId(), recent.getId());
        assertThat(rows.get(1).content()).isEqualTo("최근 글");
    }

    /** 005 T034: 숨긴 글은 쓴 회원에게만, 다른 사람에게는 보이는 답글이 남았을 때만(빈 자리) 목록에 나온다. */
    @Test
    void hiddenEntriesAppearOnlyForTheirAuthorOrAsPlaceholders() {
        GuestbookEntry hiddenWithReply = entry(new GuestbookEntry(blog, visitor, null, "숨긴 글", false), 1);
        GuestbookEntry hiddenAlone = entry(new GuestbookEntry(blog, visitor, null, "답글 없는 숨긴 글", false), 2);
        GuestbookEntry open = entry(new GuestbookEntry(blog, owner, null, "공개", false), 3);
        entry(new GuestbookEntry(blog, owner, hiddenWithReply, "주인 답글", false), 4);
        GuestbookEntry hiddenReply = entry(new GuestbookEntry(blog, visitor, open, "숨긴 답글", false), 5);
        hiddenWithReply.hide();
        hiddenAlone.hide();
        hiddenReply.hide();
        fx.flushAndClear();

        assertThat(repository.findPage(blog.getId(), owner.getId(), PageRequest.of(0, 20)).getContent())
                .extracting(GuestbookRow::id).containsExactly(open.getId(), hiddenWithReply.getId());
        Page<GuestbookRow> mine = repository.findPage(blog.getId(), visitor.getId(), PageRequest.of(0, 20));
        assertThat(mine.getContent()).extracting(GuestbookRow::id)
                .containsExactly(open.getId(), hiddenAlone.getId(), hiddenWithReply.getId());
        assertThat(mine.getTotalElements()).isEqualTo(3);
        assertThat(repository.findReplies(List.of(open.getId()), null)).isEmpty();
        assertThat(repository.findReplies(List.of(open.getId()), visitor.getId())).extracting(GuestbookRow::id)
                .containsExactly(hiddenReply.getId());
    }

    private GuestbookEntry entry(GuestbookEntry entry, int minutes) {
        em.persist(entry);
        em.flush();
        jdbc.update("UPDATE guestbook_entries SET created_at = ? WHERE id = ?",
                Timestamp.from(T0.plusSeconds(60L * minutes)), entry.getId());
        return entry;
    }
}
