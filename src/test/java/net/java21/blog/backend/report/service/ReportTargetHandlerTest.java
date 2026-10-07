package net.java21.blog.backend.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import jakarta.persistence.EntityManager;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.comment.repository.CommentRepository;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.guestbook.repository.GuestbookEntryRepository;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostStatus;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.support.JpaFixtures;
import net.java21.blog.backend.support.JpaRepositoryTest;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.repository.TrackbackRepository;
import net.java21.blog.backend.user.domain.User;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 005 T029: 대상 처리기 네 종류. 신고자가 볼 수 없는 대상은 404 {@code REPORT_TARGET_NOT_FOUND}, 자기 콘텐츠 422, 대상 작성 회원·블로그,
 * 관리자 조회(숨김 포함, 삭제 404 {@code CONTENT_NOT_FOUND}), 숨김·해제 멱등과 댓글 수 맞춤, 종류 선택(400·422).
 */
@JpaRepositoryTest
class ReportTargetHandlerTest {

    @Autowired
    private EntityManager em;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private GuestbookEntryRepository guestbookRepository;
    @Autowired
    private TrackbackRepository trackbackRepository;

    private JpaFixtures fx;
    private User owner;
    private User reporter;
    private User other;
    private Blog blog;
    private Post post;
    private PostTargetHandler posts;
    private CommentTargetHandler comments;
    private GuestbookTargetHandler guestbook;
    private TrackbackTargetHandler trackbacks;

    @BeforeEach
    void setUp() {
        fx = new JpaFixtures(em);
        owner = fx.user("owner");
        reporter = fx.user("reporter");
        other = fx.user("other");
        blog = fx.blog(owner, "owner");
        post = fx.published(blog, "글", null, 0);
        posts = new PostTargetHandler(postRepository);
        comments = new CommentTargetHandler(commentRepository);
        guestbook = new GuestbookTargetHandler(guestbookRepository);
        trackbacks = new TrackbackTargetHandler(trackbackRepository);
    }

    @Test
    void postTargetsFollowDetailVisibility() {
        Post privatePost = fx.post(blog, "비공개", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 1);
        Post hidden = fx.published(blog, "숨김", null, 2);
        hidden.hide();
        Post trashed = fx.post(blog, "휴지통", null, PostStatus.DELETED, PostVisibility.PUBLIC, 3);
        fx.flushAndClear();

        ReportTarget target = posts.resolveForReporter(post.getId(), reporter.getId());
        assertThat(target.type()).isEqualTo(ReportTargetType.POST);
        assertThat(target.targetUser().getId()).isEqualTo(owner.getId());
        assertThat(target.targetBlog().getId()).isEqualTo(blog.getId());
        assertThat(target.postId()).isEqualTo(post.getId());
        assertThat(target.blogHandle()).isEqualTo("owner");

        assertError(() -> posts.resolveForReporter(privatePost.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertError(() -> posts.resolveForReporter(hidden.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertError(() -> posts.resolveForReporter(999_999L, reporter.getId()), ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertError(() -> posts.resolveForReporter(post.getId(), owner.getId()), ErrorCode.CANNOT_REPORT_OWN_CONTENT);

        assertThat(posts.resolveForAdmin(hidden.getId()).id()).isEqualTo(hidden.getId());
        assertError(() -> posts.resolveForAdmin(trashed.getId()), ErrorCode.CONTENT_NOT_FOUND);
    }

    @Test
    void postHideRemembersStatusAndIsIdempotent() {
        assertThat(posts.hide(post.getId())).isEqualTo(new HideChange("PUBLISHED", "HIDDEN", true));
        fx.flushAndClear();
        assertThat(posts.hide(post.getId())).isEqualTo(new HideChange("HIDDEN", "HIDDEN", false));
        assertThat(posts.unhide(post.getId())).isEqualTo(new HideChange("HIDDEN", "PUBLISHED", true));
        fx.flushAndClear();
        assertThat(posts.unhide(post.getId())).isEqualTo(new HideChange("PUBLISHED", "PUBLISHED", false));
        assertError(() -> posts.hide(999_999L), ErrorCode.CONTENT_NOT_FOUND);
    }

    @Test
    void commentTargetsAndCountAdjustment() {
        Comment visible = comment(new Comment(post, other, null, "댓글"));
        Comment secret = comment(Comment.byGuest(post, null, "손님", "$2a$x", "203.0.113.1", "비밀", true));
        Comment mine = comment(new Comment(post, reporter, null, "내 댓글"));
        Comment deleted = comment(new Comment(post, other, null, "지운 댓글"));
        deleted.markDeleted();
        em.createQuery("update Post p set p.commentCount = 3 where p.id = :id").setParameter("id", post.getId())
                .executeUpdate();
        fx.flushAndClear();

        ReportTarget target = comments.resolveForReporter(visible.getId(), reporter.getId());
        assertThat(target.targetUser().getId()).isEqualTo(other.getId());
        assertThat(target.postId()).isEqualTo(post.getId());
        assertError(() -> comments.resolveForReporter(secret.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertThat(comments.resolveForReporter(secret.getId(), owner.getId()).targetUser()).isNull();
        assertError(() -> comments.resolveForReporter(mine.getId(), reporter.getId()),
                ErrorCode.CANNOT_REPORT_OWN_CONTENT);
        assertError(() -> comments.resolveForReporter(deleted.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertError(() -> comments.resolveForAdmin(deleted.getId()), ErrorCode.CONTENT_NOT_FOUND);

        assertThat(comments.hide(visible.getId())).isEqualTo(new HideChange("ACTIVE", "HIDDEN", true));
        assertThat(comments.hide(visible.getId()).changed()).isFalse();
        fx.flushAndClear();
        assertThat(commentCount()).isEqualTo(2);
        assertError(() -> comments.resolveForReporter(visible.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertThat(comments.resolveForAdmin(visible.getId()).id()).isEqualTo(visible.getId());
        assertThat(comments.unhide(visible.getId())).isEqualTo(new HideChange("HIDDEN", "ACTIVE", true));
        assertThat(comments.unhide(visible.getId()).changed()).isFalse();
        fx.flushAndClear();
        assertThat(commentCount()).isEqualTo(3);
    }

    @Test
    void guestbookTargetsFollowBlogAndSecretRules() {
        GuestbookEntry open = entry(new GuestbookEntry(blog, other, null, "방명록", false));
        GuestbookEntry secret = entry(new GuestbookEntry(blog, other, null, "비밀", true));
        GuestbookEntry reply = entry(new GuestbookEntry(blog, owner, secret, "답글", false));
        GuestbookEntry mine = entry(new GuestbookEntry(blog, reporter, null, "내 글", false));
        fx.flushAndClear();

        ReportTarget target = guestbook.resolveForReporter(open.getId(), reporter.getId());
        assertThat(target.postId()).isNull();
        assertThat(target.blogHandle()).isEqualTo("owner");
        assertThat(target.targetUser().getId()).isEqualTo(other.getId());
        assertError(() -> guestbook.resolveForReporter(secret.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertError(() -> guestbook.resolveForReporter(reply.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertThat(guestbook.resolveForReporter(reply.getId(), other.getId()).targetUser().getId())
                .isEqualTo(owner.getId());
        assertError(() -> guestbook.resolveForReporter(mine.getId(), reporter.getId()),
                ErrorCode.CANNOT_REPORT_OWN_CONTENT);

        assertThat(guestbook.hide(open.getId())).isEqualTo(new HideChange("ACTIVE", "HIDDEN", true));
        fx.flushAndClear();
        assertError(() -> guestbook.resolveForReporter(open.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertThat(guestbook.resolveForAdmin(open.getId()).id()).isEqualTo(open.getId());
        assertThat(guestbook.unhide(open.getId())).isEqualTo(new HideChange("HIDDEN", "ACTIVE", true));
        fx.flushAndClear();

        Blog closed = em.find(Blog.class, blog.getId());
        closed.changeGuestSettings(false, false);
        fx.flushAndClear();
        assertError(() -> guestbook.resolveForReporter(open.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        em.find(User.class, owner.getId()).suspend();
        em.find(Blog.class, blog.getId()).changeGuestSettings(true, false);
        fx.flushAndClear();
        assertError(() -> guestbook.resolveForReporter(open.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        GuestbookEntry gone = em.find(GuestbookEntry.class, mine.getId());
        gone.markDeleted();
        fx.flushAndClear();
        assertError(() -> guestbook.unhide(mine.getId()), ErrorCode.CONTENT_NOT_FOUND);
    }

    @Test
    void trackbackTargetsUseTheSourceAuthorOrNothing() {
        Blog sourceBlog = fx.blog(other, "other");
        Post source = fx.published(sourceBlog, "보낸 글", null, 1);
        Trackback internal = trackback(new Trackback(post, source, "https://blog.java21.net/other/" + source.getId(),
                "a".repeat(64), "보낸 글", "요약", "other", null));
        Trackback external = trackback(new Trackback(post, null, "https://ext.example/1", "b".repeat(64), "외부",
                null, null, "203.0.113.5"));
        Post privateSource = fx.post(sourceBlog, "비공개 출처", null, PostStatus.PUBLISHED, PostVisibility.PRIVATE, 2);
        Trackback fromPrivate = trackback(new Trackback(post, privateSource, "https://blog.java21.net/other/"
                + privateSource.getId(), "c".repeat(64), "비공개", null, null, null));
        fx.flushAndClear();

        ReportTarget inside = trackbacks.resolveForReporter(internal.getId(), reporter.getId());
        assertThat(inside.targetUser().getId()).isEqualTo(other.getId());
        assertThat(inside.targetBlog().getId()).isEqualTo(sourceBlog.getId());
        assertThat(inside.postId()).isEqualTo(post.getId());
        assertThat(inside.blogHandle()).isEqualTo("owner");
        ReportTarget outside = trackbacks.resolveForReporter(external.getId(), reporter.getId());
        assertThat(outside.targetUser()).isNull();
        assertThat(outside.targetBlog()).isNull();
        assertError(() -> trackbacks.resolveForReporter(fromPrivate.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertError(() -> trackbacks.resolveForReporter(external.getId(), owner.getId()),
                ErrorCode.CANNOT_REPORT_OWN_CONTENT);

        assertThat(trackbacks.hide(external.getId())).isEqualTo(new HideChange("ACTIVE", "HIDDEN", true));
        fx.flushAndClear();
        assertError(() -> trackbacks.resolveForReporter(external.getId(), reporter.getId()),
                ErrorCode.REPORT_TARGET_NOT_FOUND);
        assertThat(trackbacks.resolveForAdmin(external.getId()).id()).isEqualTo(external.getId());
        assertThat(trackbacks.unhide(external.getId())).isEqualTo(new HideChange("HIDDEN", "ACTIVE", true));
        fx.flushAndClear();
        em.find(Trackback.class, external.getId()).markDeleted();
        fx.flushAndClear();
        assertError(() -> trackbacks.hide(external.getId()), ErrorCode.CONTENT_NOT_FOUND);
    }

    @Test
    void handlerRegistryValidatesTypes() {
        ReportTargetHandlers handlers = new ReportTargetHandlers(List.of(posts, comments, guestbook, trackbacks));
        assertThat(handlers.supported()).containsExactlyInAnyOrder(ReportTargetType.POST, ReportTargetType.COMMENT,
                ReportTargetType.GUESTBOOK, ReportTargetType.TRACKBACK);
        assertThat(handlers.require("COMMENT", "targetType")).isSameAs(comments);
        assertThat(handlers.find(null)).isEmpty();
        assertThat(handlers.find(ReportTargetType.EXTERNAL_POST)).isEmpty();
        assertThatThrownBy(() -> handlers.require(" ", "targetType")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.fieldErrors()).singleElement().satisfies(f -> {
                    assertThat(f.field()).isEqualTo("targetType");
                    assertThat(f.code()).isEqualTo("REQUIRED");
                }));
        assertThatThrownBy(() -> handlers.require(null, "targetType")).isInstanceOf(BusinessException.class);
        for (String raw : new String[] {"EXTERNAL_POST", "post", "NOPE"}) {
            assertThatThrownBy(() -> handlers.require(raw, "targetType"))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(e.fieldErrors()).singleElement().satisfies(f -> {
                            assertThat(f.code()).isEqualTo("INVALID");
                            assertThat(f.params()).containsKey("allowed");
                        });
                    });
        }
        assertThat(handlers.require(ReportTargetType.POST)).isSameAs(posts);
        assertError(() -> handlers.require(ReportTargetType.EXTERNAL_BLOG), ErrorCode.REPORT_ACTION_NOT_ALLOWED);
        assertThatThrownBy(() -> new ReportTargetHandlers(List.of(posts, posts)))
                .isInstanceOf(IllegalStateException.class);
    }

    private long commentCount() {
        return em.find(Post.class, post.getId()).getCommentCount();
    }

    private Comment comment(Comment comment) {
        em.persist(comment);
        return comment;
    }

    private GuestbookEntry entry(GuestbookEntry entry) {
        em.persist(entry);
        return entry;
    }

    private Trackback trackback(Trackback trackback) {
        em.persist(trackback);
        return trackback;
    }

    private static void assertError(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
