package net.java21.blog.backend.comment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.github.benmanes.caffeine.cache.Ticker;

import net.java21.blog.backend.block.service.BlogBlockPolicy;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.comment.dto.CommentResponse;
import net.java21.blog.backend.comment.dto.CreateCommentRequest;
import net.java21.blog.backend.comment.dto.UpdateCommentRequest;
import net.java21.blog.backend.comment.event.CommentCreatedEvent;
import net.java21.blog.backend.comment.repository.CommentQueryRepository;
import net.java21.blog.backend.comment.repository.CommentRepository;
import net.java21.blog.backend.comment.repository.CommentRow;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.security.PasswordAttemptGuard;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.guest.service.GuestAuthorService;
import net.java21.blog.backend.guest.service.GuestWriteGuard;
import net.java21.blog.backend.post.PostsProperties;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.domain.UserStatus;
import net.java21.blog.backend.user.repository.UserRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 댓글 규칙(T185, FR-027~029, US3 AS1~4): 볼 수 있는 글에만(노출 매트릭스, 아니면 404 {@code POST_NOT_FOUND}),
 * 일반 텍스트 1~1000자, 답글은 같은 글의 최상위 댓글에만(아니면 422 {@code REPLY_DEPTH_EXCEEDED}),
 * 블로그·글의 댓글 허용이 꺼져 있으면 422 {@code COMMENTS_DISABLED}, 수정은 작성자만, 삭제는 작성자 또는 글 주인(아니면 403),
 * 답글이 있는 댓글은 자리만 남기고, {@code comment_count}는 표시되는 댓글 수로 같은 트랜잭션에서 바꾼다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T04:24:19Z");
    private static final long OWNER = 1L;
    private static final long WRITER = 2L;
    private static final long STRANGER = 3L;
    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder(4);
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.9", "JUnit");

    /** 테스트에서 시간을 움직이는 Caffeine 시계. */
    static final class FakeTicker implements Ticker {
        private long nanos;

        @Override
        public long read() {
            return nanos;
        }

        void advance(java.time.Duration duration) {
            nanos += duration.toNanos();
        }
    }

    @Mock
    private PostRepository postRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CommentRepository commentRepository;
    @Mock
    private CommentQueryRepository queryRepository;
    @Mock
    private ApplicationEventPublisher events;
    @Mock
    private GuestWriteGuard writeGuard;
    @Mock
    private BlogBlockPolicy blockPolicy;

    private CommentService service;
    private PasswordAttemptGuard attemptGuard;
    private User owner;
    private User writer;
    private Blog blog;
    private Post post;

    @BeforeEach
    void setUp() {
        attemptGuard = new PasswordAttemptGuard(PostsProperties.defaults(), new FakeTicker());
        GuestAuthorService guestAuthors = new GuestAuthorService(PASSWORD_ENCODER, writeGuard, attemptGuard);
        service = new CommentService(postRepository, userRepository, commentRepository, queryRepository, events,
                guestAuthors, blockPolicy);
        owner = TestEntities.user(OWNER, "owner@example.com", "{hash}", "주인");
        writer = TestEntities.user(WRITER, "writer@example.com", "{hash}", "작성자");
        blog = TestEntities.blog(10L, owner, "marco");
        post = published(100L, PostVisibility.PUBLIC);
        when(postRepository.findWithBlogAndOwner(100L)).thenReturn(Optional.of(post));
        when(userRepository.findById(WRITER)).thenReturn(Optional.of(writer));
        when(userRepository.findById(OWNER)).thenReturn(Optional.of(owner));
        when(commentRepository.save(any(Comment.class))).thenAnswer(invocation -> {
            Comment saved = invocation.getArgument(0);
            TestEntities.with(saved, "id", 500L);
            TestEntities.with(saved, "createdAt", NOW);
            TestEntities.with(saved, "updatedAt", NOW);
            return saved;
        });
    }

    // ---- 목록 ----

    @Test
    void listBuildsOneLevelTreeInWritingOrderWithDeletedPlaceholders() {
        when(queryRepository.findPostComments(100L)).thenReturn(List.of(
                row(1L, null, "첫 댓글", CommentStatus.ACTIVE, WRITER, "작성자"),
                row(2L, null, "지운 댓글", CommentStatus.DELETED, WRITER, "작성자"),
                row(3L, 1L, "주인 답글", CommentStatus.ACTIVE, OWNER, "주인"),
                row(4L, 2L, "남은 답글", CommentStatus.ACTIVE, STRANGER, "손님"),
                row(5L, null, "답글 없이 지워진 자리", CommentStatus.DELETED, WRITER, "작성자")));

        List<CommentResponse> comments = service.list(100L, null);

        assertThat(comments).extracting(CommentResponse::id).containsExactly(1L, 2L);
        CommentResponse first = comments.getFirst();
        assertThat(first.content()).isEqualTo("첫 댓글");
        assertThat(first.deleted()).isFalse();
        assertThat(first.author().nickname()).isEqualTo("작성자");
        assertThat(first.author().userId()).isEqualTo(WRITER);
        assertThat(first.author().profileImageUrl()).isEqualTo("/media/k3Jd9fQ2xLmA7pZ0bR5tYw");
        assertThat(first.replies()).singleElement().satisfies(reply -> {
            assertThat(reply.id()).isEqualTo(3L);
            assertThat(reply.replies()).isEmpty();
        });
        CommentResponse placeholder = comments.get(1);
        assertThat(placeholder.deleted()).isTrue();
        assertThat(placeholder.content()).isNull();
        assertThat(placeholder.author()).isNull();
        assertThat(placeholder.replies()).extracting(CommentResponse::content).containsExactly("남은 답글");
    }

    @Test
    void listOnlyForPostsTheViewerCanSee() {
        Post privatePost = published(101L, PostVisibility.PRIVATE);
        when(postRepository.findWithBlogAndOwner(101L)).thenReturn(Optional.of(privatePost));
        Post draft = TestEntities.post(102L, blog, "임시");
        when(postRepository.findWithBlogAndOwner(102L)).thenReturn(Optional.of(draft));
        Post trashed = published(103L, PostVisibility.PUBLIC);
        trashed.moveToTrash(NOW);
        when(postRepository.findWithBlogAndOwner(103L)).thenReturn(Optional.of(trashed));

        assertCode(() -> service.list(101L, null), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.list(101L, STRANGER), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.list(102L, STRANGER), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.list(103L, OWNER), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.list(999L, OWNER), ErrorCode.POST_NOT_FOUND);
        assertThat(service.list(101L, OWNER)).isEmpty();
    }

    @Test
    void suspendedAuthorsPostIsNotFound() {
        TestEntities.with(owner, "status", UserStatus.SUSPENDED);

        assertCode(() -> service.list(100L, STRANGER), ErrorCode.POST_NOT_FOUND);
        assertCode(() -> service.create(WRITER, 100L, new CreateCommentRequest("안녕", null)),
                ErrorCode.POST_NOT_FOUND);
    }

    // ---- 쓰기 ----

    @Test
    void createTopLevelCommentAndIncrementCount() {
        CommentResponse created = service.create(WRITER, 100L, new CreateCommentRequest("  좋은 글이네요\r\n감사합니다  ", null));

        ArgumentCaptor<Comment> saved = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("좋은 글이네요\n감사합니다");
        assertThat(saved.getValue().getPost()).isSameAs(post);
        assertThat(saved.getValue().isReply()).isFalse();
        verify(commentRepository).changeCommentCount(100L, 1);
        assertThat(created.id()).isEqualTo(500L);
        assertThat(created.content()).isEqualTo("좋은 글이네요\n감사합니다");
        assertThat(created.author().nickname()).isEqualTo("작성자");
        assertThat(created.deleted()).isFalse();
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.replies()).isEmpty();
        // 커밋 뒤 블로그 주인에게 NEW_COMMENT 알림을 만들 이벤트(002 T052)
        verify(events).publishEvent(new CommentCreatedEvent(500L, 100L, "글 100", 10L, OWNER, WRITER));
    }

    @Test
    void ownerCommentStillPublishesEventTheListenerSkipsIt() {
        service.create(OWNER, 100L, new CreateCommentRequest("주인 댓글", null));

        verify(events).publishEvent(new CommentCreatedEvent(500L, 100L, "글 100", 10L, OWNER, OWNER));
    }

    @Test
    void failedCreateDoesNotPublish() {
        assertThatThrownBy(() -> service.create(WRITER, 100L, new CreateCommentRequest(" ", null)))
                .isInstanceOf(BusinessException.class);

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void contentIsPlainTextStoredAsIsButControlCharactersRemoved() {
        service.create(WRITER, 100L, new CreateCommentRequest("<script>alert(1)</script>\u0000\u0007", null));

        ArgumentCaptor<Comment> saved = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(saved.capture());
        // 일반 텍스트: HTML로 해석하지 않고 그대로 저장하며 출력할 때 이스케이프한다(research R8).
        assertThat(saved.getValue().getContent()).isEqualTo("<script>alert(1)</script>");
    }

    @Test
    void blankAfterNormalizingIsValidationFailure() {
        assertThatThrownBy(() -> service.create(WRITER, 100L, new CreateCommentRequest("\u0000\u0001 ", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).singleElement()
                            .satisfies(f -> assertThat(f.field()).isEqualTo("content"))
                            .satisfies(f -> assertThat(f.code()).isEqualTo("REQUIRED"));
                });
        assertThat(CommentService.normalize("a".repeat(1000))).hasSize(1000);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void replyToTopLevelCommentOfSamePost() {
        Comment parent = comment(1L, post, writer, null);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(parent));

        CommentResponse reply = service.create(OWNER, 100L, new CreateCommentRequest("답글입니다", 1L));

        ArgumentCaptor<Comment> saved = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(saved.capture());
        assertThat(saved.getValue().getParent()).isSameAs(parent);
        assertThat(reply.author().nickname()).isEqualTo("주인");
        verify(commentRepository).changeCommentCount(100L, 1);
    }

    @Test
    void replyToReplyOrToAnotherPostsCommentIsRejected() {
        Comment top = comment(1L, post, writer, null);
        Comment reply = comment(2L, post, owner, top);
        Comment elsewhere = comment(3L, published(200L, PostVisibility.PUBLIC), writer, null);
        when(commentRepository.findById(2L)).thenReturn(Optional.of(reply));
        when(commentRepository.findById(3L)).thenReturn(Optional.of(elsewhere));

        assertCode(() -> service.create(WRITER, 100L, new CreateCommentRequest("답글의 답글", 2L)),
                ErrorCode.REPLY_DEPTH_EXCEEDED);
        assertCode(() -> service.create(WRITER, 100L, new CreateCommentRequest("다른 글", 3L)),
                ErrorCode.REPLY_DEPTH_EXCEEDED);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void replyToMissingOrDeletedCommentIsNotFound() {
        Comment deleted = comment(1L, post, writer, null);
        deleted.markDeleted();
        when(commentRepository.findById(1L)).thenReturn(Optional.of(deleted));
        when(commentRepository.findById(9L)).thenReturn(Optional.empty());

        assertCode(() -> service.create(WRITER, 100L, new CreateCommentRequest("답글", 1L)),
                ErrorCode.COMMENT_NOT_FOUND);
        assertCode(() -> service.create(WRITER, 100L, new CreateCommentRequest("답글", 9L)),
                ErrorCode.COMMENT_NOT_FOUND);
    }

    @Test
    void commentsDisabledByBlogOrPost() {
        blog.changeCommentEnabled(false);
        assertCode(() -> service.create(WRITER, 100L, new CreateCommentRequest("막힘", null)),
                ErrorCode.COMMENTS_DISABLED);

        blog.changeCommentEnabled(true);
        Post closed = TestEntities.post(104L, blog, "닫힌 글");
        closed.publish("닫힌 글", "본문", "<p>본문</p>", "본문", "본문", null, PostVisibility.PUBLIC, false, NOW);
        when(postRepository.findWithBlogAndOwner(104L)).thenReturn(Optional.of(closed));
        assertCode(() -> service.create(WRITER, 104L, new CreateCommentRequest("막힘", null)),
                ErrorCode.COMMENTS_DISABLED);

        Post draft = TestEntities.post(105L, blog, "임시");
        when(postRepository.findWithBlogAndOwner(105L)).thenReturn(Optional.of(draft));
        assertCode(() -> service.create(OWNER, 105L, new CreateCommentRequest("발행 전", null)),
                ErrorCode.COMMENTS_DISABLED);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void cannotCommentOnInvisiblePostOrAsInactiveMember() {
        Post privatePost = published(101L, PostVisibility.PRIVATE);
        when(postRepository.findWithBlogAndOwner(101L)).thenReturn(Optional.of(privatePost));
        assertCode(() -> service.create(WRITER, 101L, new CreateCommentRequest("비공개", null)),
                ErrorCode.POST_NOT_FOUND);

        when(userRepository.findById(STRANGER)).thenReturn(Optional.empty());
        assertCode(() -> service.create(STRANGER, 100L, new CreateCommentRequest("누구", null)),
                ErrorCode.UNAUTHENTICATED);
        TestEntities.with(writer, "status", UserStatus.WITHDRAWN);
        assertCode(() -> service.create(WRITER, 100L, new CreateCommentRequest("탈퇴", null)),
                ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void ownerCanCommentOnOwnPrivatePost() {
        Post privatePost = published(101L, PostVisibility.PRIVATE);
        when(postRepository.findWithBlogAndOwner(101L)).thenReturn(Optional.of(privatePost));

        assertThat(service.create(OWNER, 101L, new CreateCommentRequest("메모", null)).content()).isEqualTo("메모");
    }

    // ---- 수정 ----

    @Test
    void onlyAuthorCanEdit() {
        Comment comment = comment(1L, post, writer, null);
        TestEntities.with(comment, "createdAt", NOW);
        when(commentRepository.findWithPostAndOwner(1L)).thenReturn(Optional.of(comment));

        assertCode(() -> service.update(OWNER, 1L, new UpdateCommentRequest("주인이 고침")), ErrorCode.FORBIDDEN);
        assertCode(() -> service.update(STRANGER, 1L, new UpdateCommentRequest("남이 고침")), ErrorCode.FORBIDDEN);

        CommentResponse edited = service.update(WRITER, 1L, new UpdateCommentRequest(" 고친 내용 "));

        assertThat(comment.getContent()).isEqualTo("고친 내용");
        assertThat(edited.content()).isEqualTo("고친 내용");
        assertThat(edited.author().userId()).isEqualTo(WRITER);
        verify(commentRepository, never()).changeCommentCount(anyLong(), anyInt());
    }

    @Test
    void editMissingDeletedOrHiddenPostCommentIsNotFound() {
        Comment deleted = comment(1L, post, writer, null);
        deleted.markDeleted();
        when(commentRepository.findWithPostAndOwner(1L)).thenReturn(Optional.of(deleted));
        Post privatePost = published(101L, PostVisibility.PRIVATE);
        Comment onPrivate = comment(2L, privatePost, writer, null);
        when(commentRepository.findWithPostAndOwner(2L)).thenReturn(Optional.of(onPrivate));

        assertCode(() -> service.update(WRITER, 1L, new UpdateCommentRequest("x")), ErrorCode.COMMENT_NOT_FOUND);
        assertCode(() -> service.update(WRITER, 2L, new UpdateCommentRequest("x")), ErrorCode.COMMENT_NOT_FOUND);
        assertCode(() -> service.update(WRITER, 9L, new UpdateCommentRequest("x")), ErrorCode.COMMENT_NOT_FOUND);
    }

    // ---- 삭제 ----

    @Test
    void authorDeletesCommentWithoutRepliesRowIsRemoved() {
        Comment comment = comment(1L, post, writer, null);
        when(commentRepository.findWithPostAndOwner(1L)).thenReturn(Optional.of(comment));
        when(commentRepository.existsByParentId(1L)).thenReturn(false);

        service.delete(WRITER, 1L);

        verify(commentRepository).delete(comment);
        verify(commentRepository).changeCommentCount(100L, -1);
    }

    @Test
    void postOwnerDeletesOthersCommentWithRepliesLeavesPlaceholder() {
        Comment comment = comment(1L, post, writer, null);
        when(commentRepository.findWithPostAndOwner(1L)).thenReturn(Optional.of(comment));
        when(commentRepository.existsByParentId(1L)).thenReturn(true);

        service.delete(OWNER, 1L);

        assertThat(comment.isDeleted()).isTrue();
        verify(commentRepository, never()).delete(any());
        verify(commentRepository).changeCommentCount(100L, -1);
    }

    @Test
    void strangerCannotDelete() {
        Comment comment = comment(1L, post, writer, null);
        when(commentRepository.findWithPostAndOwner(1L)).thenReturn(Optional.of(comment));

        assertCode(() -> service.delete(STRANGER, 1L), ErrorCode.FORBIDDEN);
        verify(commentRepository, never()).changeCommentCount(anyLong(), anyInt());
    }

    @Test
    void deletingLastReplyOfDeletedCommentRemovesThePlaceholderToo() {
        Comment top = comment(1L, post, writer, null);
        top.markDeleted();
        Comment reply = comment(2L, post, writer, top);
        when(commentRepository.findWithPostAndOwner(2L)).thenReturn(Optional.of(reply));
        when(commentRepository.existsByParentIdAndIdNot(1L, 2L)).thenReturn(false);

        service.delete(WRITER, 2L);

        verify(commentRepository).delete(reply);
        verify(commentRepository).delete(top);
        // 자리만 남은 댓글은 이미 수에서 빠졌으므로 답글 1개만 뺀다.
        verify(commentRepository).changeCommentCount(100L, -1);
    }

    @Test
    void deletingReplyKeepsActiveParentOrPlaceholderWithOtherReplies() {
        Comment top = comment(1L, post, writer, null);
        Comment reply = comment(2L, post, writer, top);
        when(commentRepository.findWithPostAndOwner(2L)).thenReturn(Optional.of(reply));

        service.delete(WRITER, 2L);
        verify(commentRepository).delete(reply);
        verify(commentRepository, never()).delete(top);

        top.markDeleted();
        Comment another = comment(3L, post, writer, top);
        when(commentRepository.findWithPostAndOwner(3L)).thenReturn(Optional.of(another));
        when(commentRepository.existsByParentIdAndIdNot(1L, 3L)).thenReturn(true);
        service.delete(WRITER, 3L);
        verify(commentRepository, never()).delete(top);
    }

    @Test
    void deleteMissingOrAlreadyDeletedIsNotFound() {
        Comment deleted = comment(1L, post, writer, null);
        deleted.markDeleted();
        when(commentRepository.findWithPostAndOwner(1L)).thenReturn(Optional.of(deleted));

        assertCode(() -> service.delete(WRITER, 1L), ErrorCode.COMMENT_NOT_FOUND);
        assertCode(() -> service.delete(WRITER, 9L), ErrorCode.COMMENT_NOT_FOUND);
    }

    @Test
    void ownerCanStillDeleteWhenCommentsAreDisabled() {
        blog.changeCommentEnabled(false);
        Comment comment = comment(1L, post, writer, null);
        when(commentRepository.findWithPostAndOwner(1L)).thenReturn(Optional.of(comment));

        service.delete(OWNER, 1L);

        verify(commentRepository).delete(comment);
    }

    // ---- 004 비밀 댓글·비회원 댓글·보호 글·차단 (T081) ----

    @Test
    void secretCommentContentOnlyForPostOwnerAuthorAndParentAuthor() {
        when(queryRepository.findPostComments(100L)).thenReturn(List.of(
                secretRow(1L, null, "비밀", WRITER, true),
                secretRow(2L, 1L, "주인 답글", OWNER, false),
                secretRow(3L, null, "공개", STRANGER, false),
                secretRow(4L, 3L, "비밀 답글", OWNER, true)));

        List<CommentResponse> anonymous = service.list(100L, null);
        assertThat(anonymous).extracting(CommentResponse::content).containsExactly(null, "공개");
        assertThat(anonymous.get(0).secret()).isTrue();
        assertThat(anonymous.get(0).replies()).singleElement().satisfies(reply -> {
            assertThat(reply.secret()).as("비밀 댓글의 답글은 비밀").isTrue();
            assertThat(reply.content()).isNull();
        });
        assertThat(anonymous.get(1).replies().getFirst().content()).isNull();

        List<CommentResponse> asWriter = service.list(100L, WRITER);
        assertThat(asWriter.get(0).content()).isEqualTo("비밀");
        assertThat(asWriter.get(0).replies().getFirst().content()).as("부모 작성자는 답글을 본다").isEqualTo("주인 답글");

        List<CommentResponse> asStranger = service.list(100L, STRANGER);
        assertThat(asStranger.get(0).content()).isNull();
        assertThat(asStranger.get(1).replies().getFirst().content()).as("부모 작성자").isEqualTo("비밀 답글");

        List<CommentResponse> asOwner = service.list(100L, OWNER);
        assertThat(asOwner).extracting(CommentResponse::content).containsExactly("비밀", "공개");
    }

    @Test
    void guestRowsHaveGuestAuthor() {
        when(queryRepository.findPostComments(100L)).thenReturn(List.of(
                new CommentRow(1L, null, "안녕", CommentStatus.ACTIVE, null, null, null, NOW, NOW, false, "손님")));

        CommentResponse guest = service.list(100L, null).getFirst();
        assertThat(guest.author().guest()).isTrue();
        assertThat(guest.author().nickname()).isEqualTo("손님");
        assertThat(guest.author().userId()).isNull();
    }

    @Test
    void secretMemberCommentAndReplyInheritsSecret() {
        CommentResponse created = service.create(WRITER, 100L,
                new CreateCommentRequest("비밀", null, true, null, null), CLIENT, p -> false);
        assertThat(created.secret()).isTrue();

        Comment secretParent = comment(1L, post, writer, null);
        secretParent.changeSecret(true);
        when(commentRepository.findById(1L)).thenReturn(Optional.of(secretParent));
        CommentResponse reply = service.create(OWNER, 100L, new CreateCommentRequest("답", 1L, false, null, null),
                CLIENT, p -> false);
        assertThat(reply.secret()).isTrue();
        verify(blockPolicy).requireNotBlocked(10L, WRITER);
    }

    @Test
    void guestCommentNeedsGuestWriteEnabledThenStoresHashedPassword() {
        assertCode(() -> service.create(null, 100L, guestRequest("손님", "1234"), CLIENT, p -> false),
                ErrorCode.UNAUTHENTICATED);
        verify(commentRepository, never()).save(any());

        blog.changeGuestSettings(true, true);
        CommentResponse created = service.create(null, 100L, guestRequest("손님", "1234"), CLIENT, p -> false);

        assertThat(created.author().guest()).isTrue();
        assertThat(created.author().nickname()).isEqualTo("손님");
        ArgumentCaptor<Comment> saved = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(saved.capture());
        assertThat(saved.getValue().isGuest()).isTrue();
        assertThat(saved.getValue().getUser()).isNull();
        assertThat(PASSWORD_ENCODER.matches("1234", saved.getValue().getGuestPasswordHash())).isTrue();
        verify(writeGuard).check(any(), eq("203.0.113.9"));
        ArgumentCaptor<CommentCreatedEvent> event = ArgumentCaptor.forClass(CommentCreatedEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().authorId()).isNull();
        assertThat(event.getValue().guestName()).isEqualTo("손님");
        verify(blockPolicy, never()).requireNotBlocked(any(), any());
    }

    @Test
    void guestFieldsAreValidated() {
        blog.changeGuestSettings(true, true);
        assertThatThrownBy(() -> service.create(null, 100L, guestRequest(null, "12"), CLIENT, p -> false))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.fieldErrors()).extracting(f -> f.field() + ":" + f.code())
                            .contains("guestName:REQUIRED", "guestPassword:TOO_SHORT");
                });
    }

    @Test
    void blockedMemberCannotComment() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "blocked")).when(blockPolicy).requireNotBlocked(10L, WRITER);

        assertCode(() -> service.create(WRITER, 100L, new CreateCommentRequest("안녕", null)), ErrorCode.FORBIDDEN);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void lockedProtectedPostCommentsAre403UntilUnlocked() {
        Post locked = published(101L, PostVisibility.PROTECTED);
        locked.applyProtection("$2a$04$hash");
        when(postRepository.findWithBlogAndOwner(101L)).thenReturn(Optional.of(locked));

        assertCode(() -> service.list(101L, null), ErrorCode.POST_LOCKED);
        assertCode(() -> service.list(101L, WRITER, p -> false), ErrorCode.POST_LOCKED);
        assertCode(() -> service.create(WRITER, 101L, new CreateCommentRequest("안녕", null)), ErrorCode.POST_LOCKED);
        assertThat(service.list(101L, WRITER, p -> true)).isEmpty();
        assertThat(service.list(101L, OWNER)).as("주인은 열지 않아도 본다").isEmpty();

        Comment onLocked = comment(7L, locked, writer, null);
        when(commentRepository.findWithPostAndOwner(7L)).thenReturn(Optional.of(onLocked));
        assertCode(() -> service.update(WRITER, 7L, new UpdateCommentRequest("x")), ErrorCode.POST_LOCKED);
        assertCode(() -> service.delete(WRITER, 7L), ErrorCode.POST_LOCKED);
    }

    @Test
    void guestUpdateDeleteAndUnlockNeedThePassword() {
        Comment guest = guestComment(8L, "1234");
        when(commentRepository.findWithPostAndOwner(8L)).thenReturn(Optional.of(guest));

        assertCode(() -> service.update(null, 8L, new UpdateCommentRequest("고침", true, "nope"), "v:a", "ip",
                p -> false), ErrorCode.GUEST_PASSWORD_MISMATCH);
        assertCode(() -> service.unlock(null, 8L, null, "v:a", "ip", p -> false), ErrorCode.GUEST_PASSWORD_MISMATCH);
        assertCode(() -> service.delete(null, 8L, "nope", "v:a", "ip", p -> false), ErrorCode.GUEST_PASSWORD_MISMATCH);

        CommentResponse unlocked = service.unlock(null, 8L, "1234", "v:a", "ip", p -> false);
        assertThat(unlocked.content()).isEqualTo("손님 글");
        assertThat(unlocked.secret()).isTrue();

        CommentResponse updated = service.update(null, 8L, new UpdateCommentRequest("고침", false, "1234"), "v:a",
                "ip", p -> false);
        assertThat(updated.content()).isEqualTo("고침");
        assertThat(updated.secret()).isFalse();

        service.delete(null, 8L, "1234", "v:a", "ip", p -> false);
        verify(commentRepository).delete(guest);
    }

    @Test
    void fiveWrongGuestPasswordsLockTheComment() {
        Comment guest = guestComment(8L, "1234");
        when(commentRepository.findWithPostAndOwner(8L)).thenReturn(Optional.of(guest));
        for (int i = 0; i < 5; i++) {
            assertCode(() -> service.unlock(null, 8L, "nope", "v:a", "ip", p -> false),
                    ErrorCode.GUEST_PASSWORD_MISMATCH);
        }
        assertCode(() -> service.unlock(null, 8L, "1234", "v:a", "ip", p -> false),
                ErrorCode.PASSWORD_ATTEMPTS_EXCEEDED);
    }

    @Test
    void postOwnerDeletesGuestCommentWithoutPassword() {
        Comment guest = guestComment(8L, "1234");
        when(commentRepository.findWithPostAndOwner(8L)).thenReturn(Optional.of(guest));

        service.delete(OWNER, 8L, null, null, null, p -> false);
        verify(commentRepository).delete(guest);
    }

    @Test
    void memberCommentRulesForAnonymousAndUnlock() {
        Comment member = comment(9L, post, writer, null);
        when(commentRepository.findWithPostAndOwner(9L)).thenReturn(Optional.of(member));

        assertCode(() -> service.update(null, 9L, new UpdateCommentRequest("x"), null, null, p -> false),
                ErrorCode.UNAUTHENTICATED);
        assertCode(() -> service.delete(null, 9L, null, null, null, p -> false), ErrorCode.UNAUTHENTICATED);
        assertCode(() -> service.unlock(WRITER, 9L, "1234", null, null, p -> false), ErrorCode.FORBIDDEN);

        CommentResponse updated = service.update(WRITER, 9L, new UpdateCommentRequest("비밀로", true, null), null,
                null, p -> false);
        assertThat(updated.secret()).isTrue();
        assertThat(updated.author().guest()).isFalse();
    }

    private Comment guestComment(long id, String password) {
        Comment guest = Comment.byGuest(post, null, "손님", PASSWORD_ENCODER.encode(password), "enc-ip", "손님 글",
                true);
        return TestEntities.with(guest, "id", id);
    }

    private static CreateCommentRequest guestRequest(String name, String password) {
        return new CreateCommentRequest("안녕", null, false, name, password);
    }

    private static CommentRow secretRow(Long id, Long parentId, String content, Long userId, boolean secret) {
        return new CommentRow(id, parentId, content, CommentStatus.ACTIVE, userId, "회원" + userId, null, NOW, NOW,
                secret, null);
    }

    private Post published(long id, PostVisibility visibility) {
        Post p = TestEntities.post(id, blog, "글 " + id);
        p.publish("글 " + id, "본문", "<p>본문</p>", "본문", "본문", null, visibility, true, NOW);
        return p;
    }

    private static Comment comment(long id, Post post, User user, Comment parent) {
        Comment comment = new Comment(post, user, parent, "내용 " + id);
        return TestEntities.with(comment, "id", id);
    }

    private static CommentRow row(Long id, Long parentId, String content, CommentStatus status, Long userId,
            String nickname) {
        return new CommentRow(id, parentId, content, status, userId, nickname,
                userId == null ? null : "k3Jd9fQ2xLmA7pZ0bR5tYw", NOW, NOW);
    }

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
