package net.java21.blog.backend.comment.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.block.service.BlogBlockPolicy;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.comment.dto.CommentResponse;
import net.java21.blog.backend.comment.dto.CreateCommentRequest;
import net.java21.blog.backend.comment.dto.UpdateCommentRequest;
import net.java21.blog.backend.comment.event.CommentCreatedEvent;
import net.java21.blog.backend.comment.repository.CommentQueryRepository;
import net.java21.blog.backend.comment.repository.CommentRepository;
import net.java21.blog.backend.comment.repository.CommentRow;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.dto.AuthorResponse;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.security.AttemptTarget;
import net.java21.blog.backend.common.text.PlainTextNormalizer;
import net.java21.blog.backend.common.web.ClientInfo;
import net.java21.blog.backend.guest.dto.GuestCredentials;
import net.java21.blog.backend.guest.dto.GuestWriteKind;
import net.java21.blog.backend.guest.service.GuestAuthorService;
import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.post.service.PostUnlockCheck;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글(T195, FR-027~029, contracts/api.md 댓글 절).
 * <ul>
 *   <li>댓글은 글 상세를 볼 수 있는 사람만 읽고 쓴다(data-model "글 노출 매트릭스", {@link PostExposure#isDetailVisibleTo}).
 *       볼 수 없는 글은 "없는 글"과 같은 404 {@code POST_NOT_FOUND}다. 열지 않은 보호 글(004)은 403 {@code POST_LOCKED}.</li>
 *   <li>쓰기는 발행된 글에만 한다. 블로그 또는 글의 댓글 허용이 꺼져 있으면 422 {@code COMMENTS_DISABLED}(FR-029, FR-107).
 *       로그인 회원은 회원 댓글(그 블로그에서 차단된 회원은 일반 403, 004 FR-146), 비로그인은 블로그가 허용할 때만 비회원 댓글
 *       ({@link GuestAuthorService}: 이름·비밀번호·IP·쓰기 속도, 004 FR-066).</li>
 *   <li>답글은 같은 글의 최상위 댓글에만 단다(1단계, 아니면 422 {@code REPLY_DEPTH_EXCEEDED}).</li>
 *   <li>비밀 댓글(004 FR-065)의 내용은 {@link CommentVisibility}가 정한 사람에게만 준다. 비밀 댓글의 답글도 비밀이다.</li>
 *   <li>수정은 작성자만(비회원은 비밀번호), 삭제는 작성자 또는 글 주인만(FR-028, 아니면 403, 비회원 비밀번호가 틀리면 403
 *       {@code GUEST_PASSWORD_MISMATCH}). 답글이 있는 댓글은 "삭제된 댓글" 자리로 남기고, 없으면 행을 지운다. 마지막 답글이 지워지면
 *       자리만 남은 부모도 지운다.</li>
 *   <li>{@code posts.comment_count}는 표시되는 댓글(답글 포함) 수이며 쓰기·삭제와 같은 트랜잭션에서 바꾼다("구현 전 결정 사항" 5번).</li>
 *   <li>내용은 HTML을 받지 않는 일반 텍스트다. 제어 문자만 지우고 그대로 저장하며 front가 출력할 때 이스케이프한다(research R8).</li>
 *   <li>댓글·답글을 저장하면 {@link CommentCreatedEvent}를 발행한다. 커밋 뒤 블로그 주인에게 알림을 만든다(002 research D3).</li>
 * </ul>
 */
@Service
public class CommentService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final CommentQueryRepository queryRepository;
    private final ApplicationEventPublisher events;
    private final GuestAuthorService guestAuthors;
    private final BlogBlockPolicy blockPolicy;

    public CommentService(PostRepository postRepository, UserRepository userRepository,
            CommentRepository commentRepository, CommentQueryRepository queryRepository,
            ApplicationEventPublisher events, GuestAuthorService guestAuthors, BlogBlockPolicy blockPolicy) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.commentRepository = commentRepository;
        this.queryRepository = queryRepository;
        this.events = events;
        this.guestAuthors = guestAuthors;
        this.blockPolicy = blockPolicy;
    }

    /** 글의 댓글 트리(작성순, 답글은 {@code replies}). 쿼리 2회(글, 댓글·작성자) — 댓글 수와 관계없다. */
    @Transactional(readOnly = true)
    public List<CommentResponse> list(Long postId, Long viewerId, PostUnlockCheck unlock) {
        Post post = requireVisiblePost(postId, viewerId, unlock);
        return toTree(queryRepository.findPostComments(post.getId()), viewerId, post.getBlog().getUser().getId());
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> list(Long postId, Long viewerId) {
        return list(postId, viewerId, PostUnlockCheck.NONE);
    }

    /** 회원({@code userId}) 또는 비회원({@code userId} null) 댓글을 쓴다. */
    @Transactional
    public CommentResponse create(Long userId, Long postId, CreateCommentRequest request, ClientInfo client,
            PostUnlockCheck unlock) {
        Post post = requireVisiblePost(postId, userId, unlock);
        if (!post.isPublished() || !post.isCommentEnabled() || !post.getBlog().isCommentEnabled()) {
            throw new BusinessException(ErrorCode.COMMENTS_DISABLED, "Comments are disabled on post: " + postId);
        }
        String content = normalize(request.content());
        Comment parent = request.parentId() == null ? null : requireReplyTarget(post, request.parentId());
        boolean secret = CommentVisibility.isSecret(Boolean.TRUE.equals(request.secret()),
                parent == null ? null : parent.isSecret());
        Comment comment;
        AuthorResponse author;
        if (userId == null) {
            guestAuthors.requireGuestAllowed(post.getBlog());
            GuestCredentials guest = guestAuthors.newGuest(request.guestName(), request.guestPassword(), client,
                    GuestWriteKind.COMMENT);
            comment = commentRepository.save(Comment.byGuest(post, parent, guest.name(), guest.passwordHash(),
                    guest.ip(), content, secret));
            author = AuthorResponse.guest(guest.name());
        } else {
            User member = userRepository.findById(userId)
                    .filter(User::isActive)
                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Inactive member: " + userId));
            blockPolicy.requireNotBlocked(post.getBlog().getId(), userId);
            Comment created = new Comment(post, member, parent, content);
            created.changeSecret(secret);
            comment = commentRepository.save(created);
            author = authorOf(member);
        }
        CommentResponse response = new CommentResponse(comment.getId(), comment.getContent(), author, false,
                comment.isSecret(), comment.getCreatedAt(), comment.getUpdatedAt(), List.of());
        commentRepository.changeCommentCount(post.getId(), 1);
        events.publishEvent(new CommentCreatedEvent(comment.getId(), post.getId(), post.getTitle(),
                post.getBlog().getId(), post.getBlog().getUser().getId(), userId,
                userId == null ? author.nickname() : null));
        return response;
    }

    /** 회원 댓글 쓰기(001~003 호출부, 열람 쿠키 없음). */
    @Transactional
    public CommentResponse create(long userId, Long postId, CreateCommentRequest request) {
        return create(userId, postId, request, null, PostUnlockCheck.NONE);
    }

    /** 작성자만 고친다(비회원은 비밀번호). 응답의 {@code replies}는 비어 있다(목록을 다시 읽어 보여준다). */
    @Transactional
    public CommentResponse update(Long userId, Long commentId, UpdateCommentRequest request, String visitorKey,
            String ip, PostUnlockCheck unlock) {
        Comment comment = requireVisibleComment(commentId, userId, unlock);
        if (comment.isGuest()) {
            guestAuthors.verify(comment.getGuestPasswordHash(), request.guestPassword(),
                    AttemptTarget.comment(comment.getId()), visitorKey, ip);
        } else {
            requireAuthor(comment, userId);
        }
        comment.edit(normalize(request.content()));
        if (request.secret() != null) {
            comment.changeSecret(request.secret());
        }
        commentRepository.flush();
        return single(comment);
    }

    @Transactional
    public CommentResponse update(long userId, Long commentId, UpdateCommentRequest request) {
        return update(userId, commentId, request, null, null, PostUnlockCheck.NONE);
    }

    /** 작성자(비회원은 비밀번호) 또는 글 주인이 지운다. 댓글 허용이 꺼져 있어도 지울 수 있다. */
    @Transactional
    public void delete(Long userId, Long commentId, String guestPassword, String visitorKey, String ip,
            PostUnlockCheck unlock) {
        Comment comment = requireVisibleComment(commentId, userId, unlock);
        Post post = comment.getPost();
        if (!post.isOwnedBy(userId)) {
            if (comment.isGuest()) {
                guestAuthors.verify(comment.getGuestPasswordHash(), guestPassword,
                        AttemptTarget.comment(comment.getId()), visitorKey, ip);
            } else if (userId == null) {
                throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
            } else if (!comment.isWrittenBy(userId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "Cannot delete comment: " + commentId);
            }
        }
        Long postId = post.getId();
        if (comment.isReply()) {
            Comment parent = comment.getParent();
            commentRepository.delete(comment);
            if (parent.isDeleted() && !commentRepository.existsByParentIdAndIdNot(parent.getId(), comment.getId())) {
                commentRepository.delete(parent);
            }
        } else if (commentRepository.existsByParentId(comment.getId())) {
            comment.markDeleted();
        } else {
            commentRepository.delete(comment);
        }
        commentRepository.changeCommentCount(postId, -1);
    }

    @Transactional
    public void delete(long userId, Long commentId) {
        delete(userId, commentId, null, null, null, PostUnlockCheck.NONE);
    }

    /** 비회원 작성자가 비밀번호로 자기 댓글 내용을 본다(비밀 댓글 수정 전, 004 결정 표 10번). 회원 댓글은 403. */
    @Transactional(readOnly = true)
    public CommentResponse unlock(Long viewerId, Long commentId, String guestPassword, String visitorKey, String ip,
            PostUnlockCheck unlock) {
        Comment comment = requireVisibleComment(commentId, viewerId, unlock);
        if (!comment.isGuest()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not a guest comment: " + commentId);
        }
        guestAuthors.verify(comment.getGuestPasswordHash(), guestPassword, AttemptTarget.comment(comment.getId()),
                visitorKey, ip);
        return single(comment);
    }

    /**
     * 일반 텍스트 정리: 줄바꿈을 {@code \n}으로 맞추고 줄바꿈·탭이 아닌 제어 문자를 지운 뒤 앞뒤 공백을 없앤다.
     * 비면 400 {@code VALIDATION_FAILED}(content REQUIRED). HTML은 그대로 두고 출력할 때 이스케이프한다.
     */
    static String normalize(String raw) {
        String content = PlainTextNormalizer.multiline(raw);
        if (content.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Comment content is blank",
                    List.of(FieldError.of("content", "REQUIRED")));
        }
        return content;
    }

    private Post requireVisiblePost(Long postId, Long viewerId, PostUnlockCheck unlock) {
        Post post = postRepository.findWithBlogAndOwner(postId)
                .filter(p -> PostExposure.isDetailVisibleTo(p, viewerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId));
        requireUnlocked(post, viewerId, unlock);
        return post;
    }

    private static void requireUnlocked(Post post, Long viewerId, PostUnlockCheck unlock) {
        if (PostExposure.isLocked(post, viewerId, unlock.isUnlocked(post))) {
            throw new BusinessException(ErrorCode.POST_LOCKED, "Protected post is locked: " + post.getId());
        }
    }

    /** 고치거나 지울 댓글: 있고, 삭제 자리가 아니고, 그 글을 이 사람이 볼 수 있어야 한다(아니면 404). 잠긴 보호 글은 403. */
    private Comment requireVisibleComment(Long commentId, Long userId, PostUnlockCheck unlock) {
        Comment comment = commentRepository.findWithPostAndOwner(commentId)
                .filter(c -> !c.isDeleted())
                .filter(c -> PostExposure.isDetailVisibleTo(c.getPost(), userId))
                .orElseThrow(() -> commentNotFound(commentId));
        requireUnlocked(comment.getPost(), userId, unlock);
        return comment;
    }

    private static void requireAuthor(Comment comment, Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED, "Authentication required");
        }
        if (!comment.isWrittenBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not the author of comment: " + comment.getId());
        }
    }

    private Comment requireReplyTarget(Post post, Long parentId) {
        Comment parent = commentRepository.findById(parentId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> commentNotFound(parentId));
        if (parent.isReply() || !parent.getPost().getId().equals(post.getId())) {
            throw new BusinessException(ErrorCode.REPLY_DEPTH_EXCEEDED,
                    "Replies are allowed only to top-level comments of the same post: " + parentId);
        }
        return parent;
    }

    private static BusinessException commentNotFound(Long commentId) {
        return new BusinessException(ErrorCode.COMMENT_NOT_FOUND, "Comment not found: " + commentId);
    }

    private static AuthorResponse authorOf(User user) {
        return user == null ? null : AuthorResponse.member(user.getId(), user.getNickname(), user.profileImageUrl());
    }

    /** 작성자 본인 응답(수정·열기): 내용을 그대로 싣는다. */
    private static CommentResponse single(Comment comment) {
        AuthorResponse author = comment.isGuest() ? AuthorResponse.guest(comment.getGuestName())
                : authorOf(comment.getUser());
        boolean secret = CommentVisibility.isSecret(comment.isSecret(),
                comment.getParent() == null ? null : comment.getParent().isSecret());
        return new CommentResponse(comment.getId(), comment.getContent(), author, false, secret,
                comment.getCreatedAt(), comment.getUpdatedAt(), List.of());
    }

    /** 작성순 행 → 1단계 트리(비밀 판단 없음, 001~003 시험용). */
    static List<CommentResponse> toTree(List<CommentRow> rows) {
        return toTree(rows, null, null);
    }

    /**
     * 작성순 행 → 1단계 트리. 답글이 하나도 남지 않은 삭제 자리는 뺀다. 비밀 댓글은 {@link CommentVisibility}로 내용을 가린다
     * ({@code viewerId}·{@code postOwnerId}가 둘 다 null이면 비밀 댓글 내용은 모두 가린다).
     */
    static List<CommentResponse> toTree(List<CommentRow> rows, Long viewerId, Long postOwnerId) {
        Map<Long, CommentRow> byId = new HashMap<>();
        for (CommentRow row : rows) {
            byId.put(row.id(), row);
        }
        Map<Long, List<CommentResponse>> replies = new LinkedHashMap<>();
        for (CommentRow row : rows) {
            if (row.parentId() != null && row.status() == CommentStatus.ACTIVE) {
                CommentRow parent = byId.get(row.parentId());
                replies.computeIfAbsent(row.parentId(), id -> new ArrayList<>())
                        .add(toResponse(row, parent, viewerId, postOwnerId, List.of()));
            }
        }
        List<CommentResponse> tree = new ArrayList<>();
        for (CommentRow row : rows) {
            if (row.parentId() != null) {
                continue;
            }
            List<CommentResponse> children = List.copyOf(replies.getOrDefault(row.id(), List.of()));
            if (row.status() == CommentStatus.ACTIVE || !children.isEmpty()) {
                tree.add(toResponse(row, null, viewerId, postOwnerId, children));
            }
        }
        return tree;
    }

    private static CommentResponse toResponse(CommentRow row, CommentRow parent, Long viewerId, Long postOwnerId,
            List<CommentResponse> replies) {
        boolean secret = CommentVisibility.isSecret(row.secret(), parent == null ? null : parent.secret());
        if (row.status() != CommentStatus.ACTIVE) {
            return new CommentResponse(row.id(), null, null, true, secret, row.createdAt(), row.updatedAt(),
                    replies);
        }
        AuthorResponse author = row.userId() == null ? AuthorResponse.guest(row.guestName())
                : AuthorResponse.member(row.userId(), row.nickname(), Media.urlOf(row.profileMediaKey()));
        boolean readable = CommentVisibility.canRead(secret, row.userId(), parent == null ? null : parent.userId(),
                viewerId, postOwnerId);
        return new CommentResponse(row.id(), readable ? row.content() : null, author, false, secret,
                row.createdAt(), row.updatedAt(), replies);
    }
}
