package net.java21.blog.backend.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import com.querydsl.jpa.impl.JPAQueryFactory;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.config.SiteProperties;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.dto.ReportTargetPreview;
import net.java21.blog.backend.report.dto.ReportTargetPreview.Author;
import net.java21.blog.backend.report.dto.ReportTargetPreview.BlogRef;
import net.java21.blog.backend.report.dto.ReportTargetPreview.State;
import net.java21.blog.backend.report.dto.TargetKey;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.support.QueryCounter;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 005 T029: 대상 미리보기. 종류마다 IN 쿼리 1회, 글은 PUBLIC만 요약, 댓글·방명록은 비밀이어도 내용, 삭제된 대상은 내용 없음, 숨김 상태, 없는
 * 대상·처리기 없는 종류는 MISSING, front 앵커 주소.
 */
@JpaRepositoryTest
class ReportTargetPreviewRepositoryTest {

    private static final String BASE = "https://blog.example.test";

    @Autowired
    private EntityManager em;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private QueryCounter queryCounter;

    private ReportTargetPreviewRepository repository;
    private JpaFixtures fx;
    private User owner;
    private User visitor;
    private Blog blog;

    @BeforeEach
    void setUp() {
        repository = new ReportTargetPreviewRepository(queryFactory, new SiteProperties(BASE));
        fx = new JpaFixtures(em);
        owner = fx.user("owner");
        visitor = fx.user("visitor");
        blog = fx.blog(owner, "owner");
    }

    @Test
    void previewsEveryKindWithOneQueryPerType() {
        Post open = fx.published(blog, "공개 글", null, 0);
        Post secret = fx.post(blog, "비공개 글", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 1);
        Post hidden = fx.published(blog, "숨긴 글", null, 2);
        hidden.hide();
        Post trashed = fx.post(blog, "지운 글", null, PostStatus.DELETED, PostVisibility.PUBLIC, 3);
        Comment memberComment = new Comment(open, visitor, null, "회원 댓글");
        em.persist(memberComment);
        Comment guestComment = Comment.byGuest(open, null, "손님", "$2a$x", "203.0.113.1", "비밀 댓글", true);
        em.persist(guestComment);
        Comment deletedComment = new Comment(open, visitor, null, "지운 댓글");
        em.persist(deletedComment);
        deletedComment.markDeleted();
        GuestbookEntry entry = new GuestbookEntry(blog, visitor, null, "비밀 방명록", true);
        em.persist(entry);
        entry.hide();
        GuestbookEntry guestEntry = GuestbookEntry.byGuest(blog, "손님", "$2a$x", "203.0.113.1", "손님 글", false);
        em.persist(guestEntry);
        Trackback trackback = new Trackback(open, null, "https://ext.example/p/1", "a".repeat(64), "외부 글", "요약",
                "외부 블로그", null);
        em.persist(trackback);
        fx.flushAndClear();

        List<TargetKey> keys = List.of(key(ReportTargetType.POST, open.getId()),
                key(ReportTargetType.POST, secret.getId()), key(ReportTargetType.POST, hidden.getId()),
                key(ReportTargetType.POST, trashed.getId()), key(ReportTargetType.POST, 999_999L),
                key(ReportTargetType.COMMENT, memberComment.getId()),
                key(ReportTargetType.COMMENT, guestComment.getId()),
                key(ReportTargetType.COMMENT, deletedComment.getId()),
                key(ReportTargetType.GUESTBOOK, entry.getId()), key(ReportTargetType.GUESTBOOK, guestEntry.getId()),
                key(ReportTargetType.TRACKBACK, trackback.getId()), key(ReportTargetType.EXTERNAL_POST, 1L),
                new TargetKey(null, null));

        queryCounter.reset();
        Map<TargetKey, ReportTargetPreview> previews = repository.previews(keys);
        assertThat(queryCounter.count()).isEqualTo(4);
        assertThat(previews).hasSize(keys.size());
        BlogRef blogRef = new BlogRef("owner", "owner 블로그");

        assertThat(previews.get(keys.get(0))).isEqualTo(new ReportTargetPreview(ReportTargetType.POST, open.getId(),
                State.ACTIVE, "공개 글", "요약 공개 글", BASE + "/owner/" + open.getId(),
                new Author(owner.getId(), "owner", false), blogRef));
        assertThat(previews.get(keys.get(1)).text()).isNull();
        assertThat(previews.get(keys.get(2)).state()).isEqualTo(State.HIDDEN);
        assertThat(previews.get(keys.get(3)).state()).isEqualTo(State.DELETED);
        assertThat(previews.get(keys.get(4))).isEqualTo(ReportTargetPreview.missing(ReportTargetType.POST, 999_999L));

        assertThat(previews.get(keys.get(5))).isEqualTo(new ReportTargetPreview(ReportTargetType.COMMENT,
                memberComment.getId(), State.ACTIVE, "공개 글", "회원 댓글",
                BASE + "/owner/" + open.getId() + "#comment-" + memberComment.getId(),
                new Author(visitor.getId(), "visitor", false), blogRef));
        assertThat(previews.get(keys.get(6)).text()).isEqualTo("비밀 댓글");
        assertThat(previews.get(keys.get(6)).author()).isEqualTo(new Author(null, "손님", true));
        assertThat(previews.get(keys.get(7)).state()).isEqualTo(State.DELETED);
        assertThat(previews.get(keys.get(7)).text()).isNull();

        assertThat(previews.get(keys.get(8))).isEqualTo(new ReportTargetPreview(ReportTargetType.GUESTBOOK,
                entry.getId(), State.HIDDEN, null, "비밀 방명록", BASE + "/owner/guestbook#guestbook-" + entry.getId(),
                new Author(visitor.getId(), "visitor", false), blogRef));
        assertThat(previews.get(keys.get(9)).author().guest()).isTrue();

        assertThat(previews.get(keys.get(10))).isEqualTo(new ReportTargetPreview(ReportTargetType.TRACKBACK,
                trackback.getId(), State.ACTIVE, "외부 글", "요약", "https://ext.example/p/1", null, blogRef));
        assertThat(previews.get(keys.get(11)).state()).isEqualTo(State.MISSING);
        assertThat(previews.get(keys.get(12)).state()).isEqualTo(State.MISSING);
    }

    @Test
    void singlePreviewAndDeletedStates() {
        Post open = fx.published(blog, "공개 글", null, 0);
        Trackback trackback = new Trackback(open, null, "https://ext.example/p/2", "b".repeat(64), "t", null, null,
                null);
        em.persist(trackback);
        trackback.markDeleted();
        GuestbookEntry entry = new GuestbookEntry(blog, visitor, null, "글", false);
        em.persist(entry);
        entry.markDeleted();
        Comment comment = new Comment(open, visitor, null, "숨긴 댓글");
        em.persist(comment);
        comment.hide();
        Trackback hiddenTrackback = new Trackback(open, null, "https://ext.example/p/3", "c".repeat(64), "t", null,
                null, null);
        em.persist(hiddenTrackback);
        hiddenTrackback.hide();
        fx.flushAndClear();

        assertThat(repository.preview(ReportTargetType.TRACKBACK, trackback.getId()).state())
                .isEqualTo(State.DELETED);
        assertThat(repository.preview(ReportTargetType.TRACKBACK, hiddenTrackback.getId()).state())
                .isEqualTo(State.HIDDEN);
        ReportTargetPreview deletedEntry = repository.preview(ReportTargetType.GUESTBOOK, entry.getId());
        assertThat(deletedEntry.state()).isEqualTo(State.DELETED);
        assertThat(deletedEntry.text()).isNull();
        assertThat(repository.preview(ReportTargetType.COMMENT, comment.getId()).state()).isEqualTo(State.HIDDEN);
        assertThat(repository.preview(ReportTargetType.EXTERNAL_BLOG, 1L).state()).isEqualTo(State.MISSING);
    }

    private static TargetKey key(ReportTargetType type, Long id) {
        return new TargetKey(type, id);
    }
}
