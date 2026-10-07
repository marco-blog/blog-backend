package net.java21.blog.backend.guestbook.service;

import static net.java21.blog.backend.support.BusinessAssertions.assertCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import net.java21.blog.backend.block.service.BlogBlockPolicy;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.security.AttemptTarget;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.guest.dto.GuestCredentials;
import net.java21.blog.backend.guest.dto.GuestWriteKind;
import net.java21.blog.backend.guest.service.GuestAuthorService;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.guestbook.dto.GuestbookEntryResponse;
import net.java21.blog.backend.guestbook.dto.GuestbookUpdateRequest;
import net.java21.blog.backend.guestbook.dto.GuestbookWriteRequest;
import net.java21.blog.backend.guestbook.repository.GuestbookEntryRepository;
import net.java21.blog.backend.guestbook.repository.GuestbookQueryRepository;
import net.java21.blog.backend.guestbook.repository.GuestbookRow;
import net.java21.blog.backend.support.MutableClock;
import net.java21.blog.backend.support.TestEntities;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** 방명록 규칙(T034, 004 FR-056~058, FR-066, research B7). */
@ExtendWith(MockitoExtension.class)
class GuestbookServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final ClientInfo CLIENT = new ClientInfo("203.0.113.7", "Mozilla/5.0");
    private static final Pageable PAGE = PageRequest.of(0, 20);

    @Mock
    private BlogAccess blogAccess;
    @Mock
    private GuestbookEntryRepository entryRepository;
    @Mock
    private GuestbookQueryRepository queryRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GuestAuthorService guestAuthors;
    @Mock
    private BlogBlockPolicy blockPolicy;

    private GuestbookService service;
    private User owner;
    private User writer;
    private Blog blog;

    @BeforeEach
    void setUp() {
        service = new GuestbookService(blogAccess, entryRepository, queryRepository, userRepository, guestAuthors,
                blockPolicy, new MutableClock(NOW));
        owner = TestEntities.user(1L);
        writer = TestEntities.user(2L);
        blog = TestEntities.blog(10L, owner, "marco");
        lenient().when(blogAccess.requireVisibleBlog("marco")).thenReturn(blog);
        lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(owner));
        lenient().when(userRepository.findById(2L)).thenReturn(Optional.of(writer));
        lenient().when(entryRepository.save(any(GuestbookEntry.class))).thenAnswer(i -> withId(i.getArgument(0), 50L));
    }

    // ---- 목록 ----

    @Test
    void listHidesSecretContentFromOthersButShowsOwnerAndAuthor() {
        stubPage(List.of(row(5L, null, "비밀 인사", true, 2L, null), row(6L, null, "공개 인사", false, null, "손님"),
                deletedRow(7L)), List.of(row(8L, 5L, "비밀 답글", false, 1L, null), row(9L, 7L, "남은 답글", false, 1L, null)));

        List<GuestbookEntryResponse> anonymous = service.list("marco", null, PAGE).getContent();
        assertThat(anonymous.get(0).content()).isNull();
        assertThat(anonymous.get(0).secret()).isTrue();
        assertThat(anonymous.get(0).replies()).singleElement().satisfies(r -> {
            assertThat(r.content()).isNull();
            assertThat(r.secret()).isTrue();
        });
        assertThat(anonymous.get(1).content()).isEqualTo("공개 인사");
        assertThat(anonymous.get(1).author().guest()).isTrue();
        assertThat(anonymous.get(1).author().nickname()).isEqualTo("손님");
        assertThat(anonymous.get(1).author().userId()).isNull();
        assertThat(anonymous.get(2).deleted()).isTrue();
        assertThat(anonymous.get(2).content()).isNull();
        assertThat(anonymous.get(2).author()).isNull();
        assertThat(anonymous.get(2).replies()).extracting(GuestbookEntryResponse::content).containsExactly("남은 답글");

        assertThat(service.list("marco", 3L, PAGE).getContent().get(0).content()).isNull();
        List<GuestbookEntryResponse> author = service.list("marco", 2L, PAGE).getContent();
        assertThat(author.get(0).content()).isEqualTo("비밀 인사");
        assertThat(author.get(0).replies().get(0).content()).isEqualTo("비밀 답글");
        assertThat(service.list("marco", 1L, PAGE).getContent().get(0).content()).isEqualTo("비밀 인사");
        assertThat(service.list("marco", 1L, PAGE).getTotalElements()).isEqualTo(3);
    }

    /** 005 숨김(T034): 작성 회원 본인에게만 내용과 hidden, 다른 사람에게는 보이는 답글이 있을 때만 빈 자리. */
    @Test
    void hiddenEntriesShowOnlyToTheirAuthor() {
        stubPage(List.of(hiddenRow(5L, null, 2L), hiddenRow(6L, null, 2L), row(7L, null, "공개", false, 3L, null)),
                List.of(row(8L, 5L, "주인 답글", false, 1L, null), hiddenRow(9L, 7L, 2L)));

        List<GuestbookEntryResponse> others = service.list("marco", 3L, PAGE).getContent();
        assertThat(others).extracting(GuestbookEntryResponse::id).containsExactly(5L, 7L);
        assertThat(others.getFirst().hidden()).isTrue();
        assertThat(others.getFirst().deleted()).isFalse();
        assertThat(others.getFirst().content()).isNull();
        assertThat(others.getFirst().author()).isNull();
        assertThat(others.getFirst().replies()).extracting(GuestbookEntryResponse::content).containsExactly("주인 답글");
        assertThat(others.get(1).replies()).isEmpty();
        assertThat(others.get(1).hidden()).isFalse();

        List<GuestbookEntryResponse> author = service.list("marco", 2L, PAGE).getContent();
        assertThat(author).extracting(GuestbookEntryResponse::id).containsExactly(5L, 6L, 7L);
        assertThat(author.get(1).content()).isEqualTo("숨긴 글");
        assertThat(author.get(1).hidden()).isTrue();
        assertThat(author.get(1).author().userId()).isEqualTo(2L);
        assertThat(author.get(2).replies()).singleElement()
                .satisfies(r -> assertThat(r.hidden()).isTrue());
    }

    @Test
    void hiddenEntriesCannotBeEditedDeletedOrRepliedTo() {
        GuestbookEntry entry = stored(new GuestbookEntry(blog, writer, null, "숨김", false), 5L);
        entry.hide();
        when(entryRepository.findById(5L)).thenReturn(Optional.of(entry));

        assertCode(() -> service.update(5L, 2L, new GuestbookUpdateRequest("고침", null, null), "u:2", null),
                ErrorCode.GUESTBOOK_ENTRY_NOT_FOUND);
        assertCode(() -> service.delete(5L, 2L, null, "u:2", null), ErrorCode.GUESTBOOK_ENTRY_NOT_FOUND);
        assertCode(() -> service.create("marco", 1L, new GuestbookWriteRequest("답글", false, 5L, null, null),
                CLIENT), ErrorCode.GUESTBOOK_ENTRY_NOT_FOUND);
    }

    @Test
    void disabledGuestbookIsNotFoundExceptForOwner() {
        blog.changeGuestSettings(false, false);
        assertCode(() -> service.list("marco", null, PAGE), ErrorCode.GUESTBOOK_DISABLED);
        assertCode(() -> service.list("marco", 2L, PAGE), ErrorCode.GUESTBOOK_DISABLED);
        stubPage(List.of(), List.of());
        assertThat(service.list("marco", 1L, PAGE).getContent()).isEmpty();
        assertCode(() -> service.create("marco", 2L, write("안녕"), CLIENT), ErrorCode.GUESTBOOK_DISABLED);
    }

    @Test
    void invisibleBlogIsBlogNotFound() {
        when(blogAccess.requireVisibleBlog("ghost"))
                .thenThrow(new BusinessException(ErrorCode.BLOG_NOT_FOUND, "Blog not found"));
        assertCode(() -> service.list("ghost", null, PAGE), ErrorCode.BLOG_NOT_FOUND);
        assertCode(() -> service.create("ghost", 2L, write("안녕"), CLIENT), ErrorCode.BLOG_NOT_FOUND);
    }

    @Test
    void dashboardStatsShowOwnerEverything() {
        when(queryRepository.countRecent(10L, NOW.minus(Duration.ofDays(7)))).thenReturn(3L);
        when(queryRepository.findRecent(10L, 5)).thenReturn(List.of(row(5L, null, "비밀", true, 2L, null)));

        GuestbookService.GuestbookStats stats = service.stats(10L, 5);

        assertThat(stats.newGuestbook7d()).isEqualTo(3);
        assertThat(stats.recentGuestbook()).singleElement().satisfies(e -> assertThat(e.content()).isEqualTo("비밀"));
    }

    // ---- 쓰기 ----

    @Test
    void memberWritesNormalizedEntry() {
        GuestbookEntryResponse created = service.create("marco", 2L,
                new GuestbookWriteRequest("  안녕\r\n하세요\u0007 ", true, null, "무시", "무시"), CLIENT);

        ArgumentCaptor<GuestbookEntry> saved = ArgumentCaptor.forClass(GuestbookEntry.class);
        verify(entryRepository).save(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("안녕\n하세요");
        assertThat(saved.getValue().isSecret()).isTrue();
        assertThat(saved.getValue().getUser()).isSameAs(writer);
        assertThat(saved.getValue().getGuestName()).isNull();
        assertThat(created.id()).isEqualTo(50L);
        assertThat(created.content()).isEqualTo("안녕\n하세요");
        assertThat(created.author().userId()).isEqualTo(2L);
        assertThat(created.author().guest()).isFalse();
        verify(guestAuthors, never()).newGuest(any(), any(), any(), any());
    }

    @Test
    void blockedMemberCannotWriteAndReasonIsHidden() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "Write not allowed")).when(blockPolicy)
                .requireNotBlocked(10L, 2L);

        BusinessException error = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.create("marco", 2L, write("안녕"), CLIENT));
        assertThat(error.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        assertThat(error.getMessage()).doesNotContainIgnoringCase("block");
        verify(entryRepository, never()).save(any());
    }

    @Test
    void guestWritingSkipsBlockCheck() {
        when(guestAuthors.newGuest("손님", "1234", CLIENT, GuestWriteKind.GUESTBOOK))
                .thenReturn(new GuestCredentials("손님", "$2a$hash", "203.0.113.7"));
        service.create("marco", null, new GuestbookWriteRequest("안녕", false, null, "손님", "1234"), CLIENT);
        verify(blockPolicy, never()).requireNotBlocked(any(), any());
    }

    @Test
    void blankOrTooLongContentIsRejected() {
        assertCode(() -> service.create("marco", 2L, write(" \u0007 "), CLIENT), ErrorCode.VALIDATION_FAILED);
        assertCode(() -> service.create("marco", 2L, write("가".repeat(1001)), CLIENT), ErrorCode.VALIDATION_FAILED);
        verify(entryRepository, never()).save(any());
    }

    @Test
    void inactiveMemberIsUnauthenticated() {
        when(userRepository.findById(4L)).thenReturn(Optional.empty());
        assertCode(() -> service.create("marco", 4L, write("안녕"), CLIENT), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void guestWritesThroughGuestAuthorService() {
        when(guestAuthors.newGuest("손님", "1234", CLIENT, GuestWriteKind.GUESTBOOK))
                .thenReturn(new GuestCredentials("손님", "$2a$hash", "203.0.113.7"));

        GuestbookEntryResponse created = service.create("marco", null,
                new GuestbookWriteRequest("놀러 왔어요", false, null, "손님", "1234"), CLIENT);

        verify(guestAuthors).requireGuestAllowed(blog);
        ArgumentCaptor<GuestbookEntry> saved = ArgumentCaptor.forClass(GuestbookEntry.class);
        verify(entryRepository).save(saved.capture());
        assertThat(saved.getValue().getGuestName()).isEqualTo("손님");
        assertThat(saved.getValue().getGuestPasswordHash()).isEqualTo("$2a$hash");
        assertThat(saved.getValue().getGuestIp()).isEqualTo("203.0.113.7");
        assertThat(saved.getValue().getUser()).isNull();
        assertThat(created.author().guest()).isTrue();
        assertThat(created.author().nickname()).isEqualTo("손님");
    }

    @Test
    void guestWritingNotAllowedIsUnauthenticated() {
        doThrow(new BusinessException(ErrorCode.UNAUTHENTICATED, "no guests")).when(guestAuthors)
                .requireGuestAllowed(blog);
        assertCode(() -> service.create("marco", null, write("안녕"), CLIENT), ErrorCode.UNAUTHENTICATED);
        verify(entryRepository, never()).save(any());
    }

    // ---- 답글 ----

    @Test
    void ownerRepliesAndReplyFollowsParentSecret() {
        GuestbookEntry parent = withId(new GuestbookEntry(blog, writer, null, "비밀 인사", true), 5L);
        when(entryRepository.findById(5L)).thenReturn(Optional.of(parent));

        GuestbookEntryResponse reply = service.create("marco", 1L,
                new GuestbookWriteRequest("반가워요", false, 5L, null, null), CLIENT);

        ArgumentCaptor<GuestbookEntry> saved = ArgumentCaptor.forClass(GuestbookEntry.class);
        verify(entryRepository).save(saved.capture());
        assertThat(saved.getValue().getParent()).isSameAs(parent);
        assertThat(saved.getValue().isSecret()).isTrue();
        assertThat(reply.secret()).isTrue();
    }

    @Test
    void replyRules() {
        GuestbookEntry parent = withId(new GuestbookEntry(blog, writer, null, "인사", false), 5L);
        GuestbookEntry child = withId(new GuestbookEntry(blog, owner, parent, "답글", false), 6L);
        Blog other = TestEntities.blog(11L, owner, "other");
        GuestbookEntry elsewhere = withId(new GuestbookEntry(other, writer, null, "남의 블로그", false), 7L);
        lenient().when(entryRepository.findById(5L)).thenReturn(Optional.of(parent));
        when(entryRepository.findById(6L)).thenReturn(Optional.of(child));
        when(entryRepository.findById(7L)).thenReturn(Optional.of(elsewhere));
        when(entryRepository.findById(99L)).thenReturn(Optional.empty());

        assertCode(() -> service.create("marco", 2L, reply(5L), CLIENT), ErrorCode.FORBIDDEN);
        assertCode(() -> service.create("marco", null, reply(5L), CLIENT), ErrorCode.UNAUTHENTICATED);
        assertCode(() -> service.create("marco", 1L, reply(6L), CLIENT), ErrorCode.REPLY_DEPTH_EXCEEDED);
        assertCode(() -> service.create("marco", 1L, reply(7L), CLIENT), ErrorCode.GUESTBOOK_ENTRY_NOT_FOUND);
        assertCode(() -> service.create("marco", 1L, reply(99L), CLIENT), ErrorCode.GUESTBOOK_ENTRY_NOT_FOUND);
        parent.markDeleted();
        assertCode(() -> service.create("marco", 1L, reply(5L), CLIENT), ErrorCode.GUESTBOOK_ENTRY_NOT_FOUND);
    }

    // ---- 수정 ----

    @Test
    void memberAuthorEditsContentAndSecret() {
        GuestbookEntry entry = stored(new GuestbookEntry(blog, writer, null, "처음", false), 5L);

        GuestbookEntryResponse updated = service.update(5L, 2L, new GuestbookUpdateRequest(" 고침 ", true, null),
                "u:2", "203.0.113.7");

        assertThat(entry.getContent()).isEqualTo("고침");
        assertThat(entry.isSecret()).isTrue();
        assertThat(updated.content()).isEqualTo("고침");
        service.update(5L, 2L, new GuestbookUpdateRequest(null, false, null), "u:2", null);
        assertThat(entry.getContent()).isEqualTo("고침");
        assertThat(entry.isSecret()).isFalse();

        assertCode(() -> service.update(5L, 3L, new GuestbookUpdateRequest("남이", null, null), "u:3", null),
                ErrorCode.FORBIDDEN);
        assertCode(() -> service.update(5L, 1L, new GuestbookUpdateRequest("주인", null, null), "u:1", null),
                ErrorCode.FORBIDDEN);
        assertCode(() -> service.update(5L, null, new GuestbookUpdateRequest("익명", null, null), null, null),
                ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void guestEditNeedsPassword() {
        GuestbookEntry entry = stored(GuestbookEntry.byGuest(blog, "손님", "$2a$hash", null, "처음", false), 5L);
        doThrow(new BusinessException(ErrorCode.GUEST_PASSWORD_MISMATCH, "mismatch")).when(guestAuthors)
                .verify("$2a$hash", "틀림", AttemptTarget.guestbook(5L), "v:abc", "203.0.113.7");

        assertCode(() -> service.update(5L, null, new GuestbookUpdateRequest("고침", null, "틀림"), "v:abc",
                "203.0.113.7"), ErrorCode.GUEST_PASSWORD_MISMATCH);
        assertThat(entry.getContent()).isEqualTo("처음");

        GuestbookEntryResponse updated = service.update(5L, null, new GuestbookUpdateRequest("고침", true, "1234"),
                "v:abc", "203.0.113.7");
        verify(guestAuthors).verify("$2a$hash", "1234", AttemptTarget.guestbook(5L), "v:abc", "203.0.113.7");
        assertThat(updated.content()).isEqualTo("고침");
        assertThat(updated.secret()).isTrue();
    }

    @Test
    void replySecretCannotBeChangedAndMissingEntryIsNotFound() {
        GuestbookEntry parent = withId(new GuestbookEntry(blog, writer, null, "인사", true), 5L);
        GuestbookEntry child = stored(new GuestbookEntry(blog, owner, parent, "답글", true), 6L);
        service.update(6L, 1L, new GuestbookUpdateRequest("고친 답글", false, null), "u:1", null);
        assertThat(child.isSecret()).isTrue();
        assertThat(child.getContent()).isEqualTo("고친 답글");

        when(entryRepository.findWithBlogAndOwner(99L)).thenReturn(Optional.empty());
        assertCode(() -> service.update(99L, 1L, new GuestbookUpdateRequest("x", null, null), null, null),
                ErrorCode.GUESTBOOK_ENTRY_NOT_FOUND);
        GuestbookEntry deleted = stored(new GuestbookEntry(blog, writer, null, "지움", false), 8L);
        deleted.markDeleted();
        assertCode(() -> service.update(8L, 2L, new GuestbookUpdateRequest("x", null, null), null, null),
                ErrorCode.GUESTBOOK_ENTRY_NOT_FOUND);
    }

    @Test
    void entriesOfHiddenBlogOrDisabledGuestbookAreHidden() {
        stored(new GuestbookEntry(blog, writer, null, "인사", false), 5L);
        blog.changeGuestSettings(false, false);
        assertCode(() -> service.update(5L, 2L, new GuestbookUpdateRequest("x", null, null), null, null),
                ErrorCode.GUESTBOOK_DISABLED);
        service.delete(5L, 1L, null, "u:1", null);
        blog.delete(NOW);
        assertCode(() -> service.delete(5L, 1L, null, "u:1", null), ErrorCode.GUESTBOOK_ENTRY_NOT_FOUND);
    }

    // ---- 삭제 ----

    @Test
    void authorOrOwnerDeletesAndEntryWithRepliesLeavesPlaceholder() {
        GuestbookEntry lone = stored(new GuestbookEntry(blog, writer, null, "혼자", false), 5L);
        service.delete(5L, 2L, null, "u:2", null);
        verify(entryRepository).delete(lone);

        GuestbookEntry withReplies = stored(new GuestbookEntry(blog, writer, null, "답글 있음", false), 6L);
        when(entryRepository.existsByParentId(6L)).thenReturn(true);
        service.delete(6L, 1L, null, "u:1", null);
        assertThat(withReplies.getStatus()).isEqualTo(GuestbookStatus.DELETED);
        verify(entryRepository, never()).delete(withReplies);

        stored(new GuestbookEntry(blog, writer, null, "남의 글", false), 7L);
        assertCode(() -> service.delete(7L, 3L, null, "u:3", null), ErrorCode.FORBIDDEN);
        assertCode(() -> service.delete(7L, null, null, null, null), ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void deletingLastReplyRemovesPlaceholderParent() {
        GuestbookEntry parent = withId(new GuestbookEntry(blog, writer, null, "부모", false), 5L);
        parent.markDeleted();
        GuestbookEntry reply = stored(new GuestbookEntry(blog, owner, parent, "마지막 답글", false), 6L);
        when(entryRepository.existsByParentIdAndIdNot(5L, 6L)).thenReturn(false);

        service.delete(6L, 1L, null, "u:1", null);

        verify(entryRepository).delete(reply);
        verify(entryRepository).delete(parent);
    }

    @Test
    void deletingReplyKeepsParentWithOtherReplies() {
        GuestbookEntry parent = withId(new GuestbookEntry(blog, writer, null, "부모", false), 5L);
        parent.markDeleted();
        stored(new GuestbookEntry(blog, owner, parent, "답글", false), 6L);
        when(entryRepository.existsByParentIdAndIdNot(5L, 6L)).thenReturn(true);

        service.delete(6L, 1L, null, "u:1", null);

        verify(entryRepository, never()).delete(parent);
    }

    @Test
    void guestDeletesWithPasswordAndOwnerWithout() {
        GuestbookEntry entry = stored(GuestbookEntry.byGuest(blog, "손님", "$2a$hash", null, "글", false), 5L);
        service.delete(5L, null, "1234", "v:abc", "203.0.113.7");
        verify(guestAuthors).verify("$2a$hash", "1234", AttemptTarget.guestbook(5L), "v:abc", "203.0.113.7");
        verify(entryRepository).delete(entry);

        stored(GuestbookEntry.byGuest(blog, "손님", "$2a$hash", null, "글2", false), 6L);
        service.delete(6L, 1L, null, "u:1", null);
        verify(guestAuthors, never()).verify(anyString(), eq(null), any(), any(), any());
    }

    // ---- 열기 ----

    @Test
    void unlockReturnsGuestContentOnlyWithPassword() {
        stored(GuestbookEntry.byGuest(blog, "손님", "$2a$hash", null, "비밀 내용", true), 5L);
        GuestbookEntryResponse unlocked = service.unlock(5L, "1234", "v:abc", "203.0.113.7");
        assertThat(unlocked.content()).isEqualTo("비밀 내용");
        verify(guestAuthors).verify("$2a$hash", "1234", AttemptTarget.guestbook(5L), "v:abc", "203.0.113.7");

        stored(new GuestbookEntry(blog, writer, null, "회원 글", true), 6L);
        assertCode(() -> service.unlock(6L, "1234", null, null), ErrorCode.FORBIDDEN);
    }

    // ---- 도우미 ----

    private void stubPage(List<GuestbookRow> tops, List<GuestbookRow> replies) {
        Page<GuestbookRow> page = new PageImpl<>(tops, PAGE, tops.size());
        when(queryRepository.findPage(eq(10L), any(), eq(PAGE))).thenReturn(page);
        when(queryRepository.findReplies(eq(tops.stream().map(GuestbookRow::id).toList()), any())).thenReturn(replies);
    }

    private GuestbookEntry stored(GuestbookEntry entry, long id) {
        withId(entry, id);
        when(entryRepository.findWithBlogAndOwner(id)).thenReturn(Optional.of(entry));
        return entry;
    }

    private static GuestbookEntry withId(GuestbookEntry entry, long id) {
        return TestEntities.with(entry, "id", id);
    }

    private static GuestbookRow row(long id, Long parentId, String content, boolean secret, Long userId,
            String guestName) {
        return new GuestbookRow(id, parentId, content, secret, GuestbookStatus.ACTIVE, userId,
                userId == null ? null : "닉네임" + userId, null, guestName, NOW, NOW);
    }

    private static GuestbookRow hiddenRow(long id, Long parentId, Long userId) {
        return new GuestbookRow(id, parentId, "숨긴 글", false, GuestbookStatus.HIDDEN, userId, "닉네임" + userId, null,
                null, NOW, NOW);
    }

    private static GuestbookRow deletedRow(long id) {
        return new GuestbookRow(id, null, "지운 내용", false, GuestbookStatus.DELETED, 2L, "닉네임2", null, null, NOW,
                NOW);
    }

    private static GuestbookWriteRequest write(String content) {
        return new GuestbookWriteRequest(content, false, null, null, null);
    }

    private static GuestbookWriteRequest reply(Long parentId) {
        return new GuestbookWriteRequest("답글", false, parentId, null, null);
    }
}
