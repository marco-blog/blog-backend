package net.java21.blog.backend.guestbook.service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.block.service.BlogBlockPolicy;
import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.dto.AuthorResponse;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.security.AttemptTarget;
import net.java21.blog.backend.common.text.PlainTextNormalizer;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.guest.dto.GuestCredentials;
import net.java21.blog.backend.guest.service.GuestAuthorService;
import net.java21.blog.backend.guestbook.domain.GuestbookEntry;
import net.java21.blog.backend.guestbook.domain.GuestbookStatus;
import net.java21.blog.backend.guestbook.dto.GuestbookEntryResponse;
import net.java21.blog.backend.guestbook.dto.GuestbookUpdateRequest;
import net.java21.blog.backend.guestbook.dto.GuestbookWriteRequest;
import net.java21.blog.backend.guestbook.repository.GuestbookEntryRepository;
import net.java21.blog.backend.guestbook.repository.GuestbookQueryRepository;
import net.java21.blog.backend.guestbook.repository.GuestbookRow;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.spam.WriteGuard;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방명록(004 FR-056~058, FR-066, contracts/api.md 방명록 절).
 * <ul>
 *   <li>볼 수 있는 블로그(001 {@link BlogAccess})의 방명록만 읽고 쓴다. 방명록을 끈 블로그는 주인 외 404 {@code GUESTBOOK_DISABLED}.</li>
 *   <li>로그인 회원은 회원 글, 비로그인은 블로그가 허용할 때만 비회원 글({@link GuestAuthorService}: 이름·비밀번호·IP·쓰기 속도).</li>
 *   <li>답글은 블로그 주인만 최상위 글에 단다(1단계). 답글의 비밀 여부는 부모를 따른다.</li>
 *   <li>비밀글 내용은 주인과 작성 회원만({@link GuestbookVisibility}). 비회원 작성자는 비밀번호로 연다({@code unlock}).</li>
 *   <li>수정은 작성자(비회원은 비밀번호), 삭제는 작성자 또는 블로그 주인. 답글이 있으면 "삭제된 글" 자리만 남기고, 마지막 답글이
 *       지워지면 자리만 남은 부모도 지운다(001 댓글과 같은 규칙).</li>
 *   <li>내용은 일반 텍스트(001 댓글 규칙, {@link PlainTextNormalizer})이며 front가 이스케이프해 출력한다.</li>
 * </ul>
 * 이 블로그에서 차단된 회원(US5 FR-146)의 쓰기는 {@link BlogBlockPolicy}가 일반 403 {@code FORBIDDEN}으로 거부한다.
 */
@Service
public class GuestbookService {

    /** 대시보드 "새 방명록" 기간 */
    static final Duration NEW_ENTRY_WINDOW = Duration.ofDays(7);

    private final BlogAccess blogAccess;
    private final GuestbookEntryRepository entryRepository;
    private final GuestbookQueryRepository queryRepository;
    private final UserRepository userRepository;
    private final GuestAuthorService guestAuthors;
    private final BlogBlockPolicy blockPolicy;
    private final WriteGuard writeGuard;
    private final Clock clock;

    public GuestbookService(BlogAccess blogAccess, GuestbookEntryRepository entryRepository,
            GuestbookQueryRepository queryRepository, UserRepository userRepository, GuestAuthorService guestAuthors,
            BlogBlockPolicy blockPolicy, WriteGuard writeGuard, Clock clock) {
        this.blogAccess = blogAccess;
        this.entryRepository = entryRepository;
        this.queryRepository = queryRepository;
        this.userRepository = userRepository;
        this.guestAuthors = guestAuthors;
        this.blockPolicy = blockPolicy;
        this.writeGuard = writeGuard;
        this.clock = clock;
    }

    /** 대시보드 방명록 수치 */
    public record GuestbookStats(long newGuestbook7d, List<GuestbookEntryResponse> recentGuestbook) {
    }

    /** 최상위 글 최신순 페이지와 각 답글. 쿼리 4회(블로그, 목록, 전체 수, 답글) — 글 수와 관계없다. */
    @Transactional(readOnly = true)
    public Page<GuestbookEntryResponse> list(String handle, Long viewerId, Pageable pageable) {
        Blog blog = blogAccess.requireVisibleBlog(handle);
        requireGuestbookOpen(blog, viewerId);
        Page<GuestbookRow> page = queryRepository.findPage(blog.getId(), viewerId, pageable);
        List<GuestbookRow> replies = queryRepository.findReplies(page.getContent().stream().map(GuestbookRow::id)
                .toList(), viewerId);
        List<GuestbookEntryResponse> tree = toTree(page.getContent(), replies, viewerId, blog.getUser().getId());
        return new PageImpl<>(tree, pageable, page.getTotalElements());
    }

    /** 대시보드: 최근 7일 새 최상위 글 수와 최근 {@code limit}건(주인이 보므로 비밀글 내용 포함). 쿼리 2회. */
    @Transactional(readOnly = true)
    public GuestbookStats stats(Long blogId, int limit) {
        long count = queryRepository.countRecent(blogId, clock.instant().minus(NEW_ENTRY_WINDOW));
        List<GuestbookEntryResponse> recent = queryRepository.findRecent(blogId, limit).stream()
                .map(row -> toResponse(row, row.secret(), true, List.of()))
                .toList();
        return new GuestbookStats(count, recent);
    }

    @Transactional
    public GuestbookEntryResponse create(String handle, Long userId, GuestbookWriteRequest request,
            ClientInfo client) {
        Blog blog = blogAccess.requireVisibleBlog(handle);
        boolean owner = blog.isOwnedBy(userId);
        requireGuestbookOpen(blog, userId);
        String content = normalize(request.content());
        String ip = client == null ? null : client.ip();
        if (request.parentId() != null) {
            return reply(blog, userId, owner, request.parentId(), content, ip);
        }
        boolean secret = Boolean.TRUE.equals(request.secret());
        GuestbookEntry entry;
        if (userId == null) {
            guestAuthors.requireGuestAllowed(blog);
            content = writeGuard.guardNew(WriteGuard.Kind.GUESTBOOK, WriteGuard.Writer.guest(ip), content,
                    request.captchaToken(), request.guestName());
            GuestCredentials guest = guestAuthors.newGuest(request.guestName(), request.guestPassword(), client);
            entry = GuestbookEntry.byGuest(blog, guest.name(), guest.passwordHash(), guest.ip(), content, secret);
        } else {
            User member = requireActiveMember(userId);
            blockPolicy.requireNotBlocked(blog.getId(), userId);
            content = writeGuard.guardNew(WriteGuard.Kind.GUESTBOOK, WriteGuard.Writer.member(member, ip), content,
                    null, null);
            entry = new GuestbookEntry(blog, member, null, content, secret);
        }
        entryRepository.save(entry);
        return single(entry);
    }

    /** 작성자만 고친다. 비회원 글은 비밀번호로. 응답은 내용을 포함한다(작성자가 보는 화면). */
    @Transactional
    public GuestbookEntryResponse update(Long entryId, Long userId, GuestbookUpdateRequest request,
            String visitorKey, String ip) {
        GuestbookEntry entry = requireEntry(entryId, userId);
        if (entry.isGuest()) {
            guestAuthors.verify(entry.getGuestPasswordHash(), request.guestPassword(),
                    AttemptTarget.guestbook(entry.getId()), visitorKey, ip);
        } else {
            requireAuthor(entry, userId);
        }
        String content = request.content() == null ? null : writeGuard.guardEdit(normalize(request.content()));
        entry.edit(content, entry.isReply() ? null : request.secret());
        entryRepository.flush();
        return single(entry);
    }

    /** 작성자(비회원은 비밀번호) 또는 블로그 주인이 지운다. */
    @Transactional
    public void delete(Long entryId, Long userId, String guestPassword, String visitorKey, String ip) {
        GuestbookEntry entry = requireEntry(entryId, userId);
        if (!entry.getBlog().isOwnedBy(userId)) {
            if (entry.isGuest()) {
                guestAuthors.verify(entry.getGuestPasswordHash(), guestPassword,
                        AttemptTarget.guestbook(entry.getId()), visitorKey, ip);
            } else {
                requireAuthor(entry, userId);
            }
        }
        if (entry.isReply()) {
            GuestbookEntry parent = entry.getParent();
            entryRepository.delete(entry);
            if (parent.isDeleted() && !entryRepository.existsByParentIdAndIdNot(parent.getId(), entry.getId())) {
                entryRepository.delete(parent);
            }
        } else if (entryRepository.existsByParentId(entry.getId())) {
            entry.markDeleted();
        } else {
            entryRepository.delete(entry);
        }
    }

    /** 비회원 작성자가 비밀번호로 자기 글 내용을 본다(비밀글 수정 전). 회원 글은 403. */
    @Transactional(readOnly = true)
    public GuestbookEntryResponse unlock(Long entryId, String guestPassword, String visitorKey, String ip) {
        GuestbookEntry entry = requireEntry(entryId, null);
        if (!entry.isGuest()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not a guest entry: " + entryId);
        }
        guestAuthors.verify(entry.getGuestPasswordHash(), guestPassword, AttemptTarget.guestbook(entry.getId()),
                visitorKey, ip);
        return single(entry);
    }

    private GuestbookEntryResponse reply(Blog blog, Long userId, boolean owner, Long parentId, String content,
            String ip) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (!owner) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Only the blog owner can reply: " + blog.getHandle());
        }
        GuestbookEntry parent = entryRepository.findById(parentId)
                .filter(p -> p.isActive() && p.getBlog().getId().equals(blog.getId()))
                .orElseThrow(() -> entryNotFound(parentId));
        if (parent.isReply()) {
            throw new BusinessException(ErrorCode.REPLY_DEPTH_EXCEEDED,
                    "Replies are allowed only to top-level entries: " + parentId);
        }
        User member = requireActiveMember(userId);
        String guarded = writeGuard.guardNew(WriteGuard.Kind.GUESTBOOK, WriteGuard.Writer.member(member, ip), content,
                null, null);
        GuestbookEntry entry = entryRepository.save(new GuestbookEntry(blog, member, parent, guarded,
                parent.isSecret()));
        return single(entry);
    }

    /** 고치거나 지울 글: 있고, 삭제 자리가 아니고, 블로그를 볼 수 있고, 방명록이 열려 있어야 한다(주인은 꺼져 있어도). */
    private GuestbookEntry requireEntry(Long entryId, Long userId) {
        GuestbookEntry entry = entryRepository.findWithBlogAndOwner(entryId)
                .filter(GuestbookEntry::isActive)
                .filter(e -> e.getBlog().isActive() && e.getBlog().getUser().isActive())
                .orElseThrow(() -> entryNotFound(entryId));
        requireGuestbookOpen(entry.getBlog(), userId);
        return entry;
    }

    private static void requireAuthor(GuestbookEntry entry, Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (!entry.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not the author of guestbook entry: " + entry.getId());
        }
    }

    private static void requireGuestbookOpen(Blog blog, Long viewerId) {
        if (!blog.isGuestbookEnabled() && !blog.isOwnedBy(viewerId)) {
            throw new BusinessException(ErrorCode.GUESTBOOK_DISABLED, "Guestbook is disabled: " + blog.getHandle());
        }
    }

    private User requireActiveMember(Long userId) {
        return userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Inactive member: " + userId));
    }

    /** 일반 텍스트 정리(001 댓글 규칙). 비면 400 {@code content REQUIRED}, 1000자 초과면 {@code TOO_LONG}. */
    static String normalize(String raw) {
        String content = PlainTextNormalizer.multiline(raw);
        if (content.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Guestbook content is blank",
                    List.of(FieldError.of("content", "REQUIRED")));
        }
        if (content.codePointCount(0, content.length()) > GuestbookEntry.CONTENT_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Guestbook content is too long",
                    List.of(new FieldError("content", "TOO_LONG", Map.of("max", GuestbookEntry.CONTENT_MAX))));
        }
        return content;
    }

    private static BusinessException entryNotFound(Long entryId) {
        return new BusinessException(ErrorCode.GUESTBOOK_ENTRY_NOT_FOUND, "Guestbook entry not found: " + entryId);
    }

    /** 작성자 본인 응답(쓰기·수정·열기): 내용을 그대로 싣는다. */
    private static GuestbookEntryResponse single(GuestbookEntry entry) {
        AuthorResponse author = entry.isGuest() ? AuthorResponse.guest(entry.getGuestName())
                : AuthorResponse.member(entry.getUser().getId(), entry.getUser().getNickname(),
                        entry.getUser().profileImageUrl());
        return new GuestbookEntryResponse(entry.getId(), entry.getContent(), entry.isSecret(), false, author,
                entry.getCreatedAt(), entry.getUpdatedAt(), List.of());
    }

    /** 최상위 행 + 답글 행 → 1단계 트리. 비밀글은 {@link GuestbookVisibility}로 내용을 가린다. */
    static List<GuestbookEntryResponse> toTree(List<GuestbookRow> tops, List<GuestbookRow> replyRows, Long viewerId,
            Long blogOwnerId) {
        Map<Long, List<GuestbookRow>> replies = new LinkedHashMap<>();
        for (GuestbookRow row : replyRows) {
            replies.computeIfAbsent(row.parentId(), id -> new ArrayList<>()).add(row);
        }
        List<GuestbookEntryResponse> tree = new ArrayList<>();
        for (GuestbookRow top : tops) {
            boolean readable = GuestbookVisibility.canRead(top.secret(), top.userId(), viewerId, blogOwnerId);
            List<GuestbookEntryResponse> children = replies.getOrDefault(top.id(), List.of()).stream()
                    .filter(r -> r.status() != GuestbookStatus.HIDDEN || hiddenForAuthor(r, viewerId))
                    .map(r -> toResponse(r, top.secret(), readable, viewerId, List.of()))
                    .toList();
            if (top.status() == GuestbookStatus.HIDDEN && !hiddenForAuthor(top, viewerId) && children.isEmpty()) {
                continue;
            }
            tree.add(toResponse(top, top.secret(), readable, viewerId, children));
        }
        return tree;
    }

    /** 답글의 비밀 여부는 부모를 따르므로 {@code secret}·{@code readable}은 최상위 글 기준으로 넘긴다. */
    private static GuestbookEntryResponse toResponse(GuestbookRow row, boolean secret, boolean readable,
            List<GuestbookEntryResponse> replies) {
        return toResponse(row, secret, readable, null, replies);
    }

    /** 숨긴 글을 쓴 회원 본인이 보는지(005 FR-041). */
    private static boolean hiddenForAuthor(GuestbookRow row, Long viewerId) {
        return row.status() == GuestbookStatus.HIDDEN && viewerId != null && viewerId.equals(row.userId());
    }

    private static GuestbookEntryResponse toResponse(GuestbookRow row, boolean secret, boolean readable,
            Long viewerId, List<GuestbookEntryResponse> replies) {
        if (row.status() == GuestbookStatus.HIDDEN) {
            if (!hiddenForAuthor(row, viewerId)) {
                return new GuestbookEntryResponse(row.id(), null, secret, false, null, row.createdAt(),
                        row.updatedAt(), replies, true);
            }
            return new GuestbookEntryResponse(row.id(), row.content(), secret, false,
                    AuthorResponse.member(row.userId(), row.nickname(), Media.urlOf(row.profileMediaKey())),
                    row.createdAt(), row.updatedAt(), replies, true);
        }
        if (row.deleted()) {
            return new GuestbookEntryResponse(row.id(), null, secret, true, null, row.createdAt(),
                    row.updatedAt(), replies);
        }
        AuthorResponse author = row.userId() == null ? AuthorResponse.guest(row.guestName())
                : AuthorResponse.member(row.userId(), row.nickname(), Media.urlOf(row.profileMediaKey()));
        return new GuestbookEntryResponse(row.id(), readable ? row.content() : null, secret, false, author,
                row.createdAt(), row.updatedAt(), replies);
    }
}
