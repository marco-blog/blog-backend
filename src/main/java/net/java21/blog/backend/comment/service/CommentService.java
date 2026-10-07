package net.java21.blog.backend.comment.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.media.domain.Media;
import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.comment.domain.CommentStatus;
import net.java21.blog.backend.common.dto.AuthorResponse;
import net.java21.blog.backend.comment.dto.CommentResponse;
import net.java21.blog.backend.comment.dto.CreateCommentRequest;
import net.java21.blog.backend.comment.dto.UpdateCommentRequest;
import net.java21.blog.backend.comment.event.CommentCreatedEvent;
import net.java21.blog.backend.comment.repository.CommentQueryRepository;
import net.java21.blog.backend.comment.repository.CommentRepository;
import net.java21.blog.backend.comment.repository.CommentRow;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.text.PlainTextNormalizer;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.user.domain.User;
import net.java21.blog.backend.user.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 댓글(T195, FR-027~029, contracts/api.md 댓글 절).
 * <ul>
 *   <li>댓글은 글 상세를 볼 수 있는 사람만 읽고 쓴다(data-model "글 노출 매트릭스", {@link PostExposure#isDetailVisibleTo}).
 *       볼 수 없는 글은 "없는 글"과 같은 404 {@code POST_NOT_FOUND}다.</li>
 *   <li>쓰기는 로그인 회원이 발행된 글에만 한다. 블로그 또는 글의 댓글 허용이 꺼져 있으면 422 {@code COMMENTS_DISABLED}(FR-029, FR-107).</li>
 *   <li>답글은 같은 글의 최상위 댓글에만 단다(1단계, 아니면 422 {@code REPLY_DEPTH_EXCEEDED}).</li>
 *   <li>수정은 작성자만, 삭제는 작성자 또는 글 주인만(FR-028, 아니면 403). 답글이 있는 댓글은 "삭제된 댓글" 자리로 남기고,
 *       없으면 행을 지운다. 마지막 답글이 지워지면 자리만 남은 부모도 지운다.</li>
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

    public CommentService(PostRepository postRepository, UserRepository userRepository,
            CommentRepository commentRepository, CommentQueryRepository queryRepository,
            ApplicationEventPublisher events) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.commentRepository = commentRepository;
        this.queryRepository = queryRepository;
        this.events = events;
    }

    /** 글의 댓글 트리(작성순, 답글은 {@code replies}). 쿼리 2회(글, 댓글·작성자) — 댓글 수와 관계없다. */
    @Transactional(readOnly = true)
    public List<CommentResponse> list(Long postId, Long viewerId) {
        Post post = requireVisiblePost(postId, viewerId);
        return toTree(queryRepository.findPostComments(post.getId()));
    }

    @Transactional
    public CommentResponse create(long userId, Long postId, CreateCommentRequest request) {
        Post post = requireVisiblePost(postId, userId);
        if (!post.isPublished() || !post.isCommentEnabled() || !post.getBlog().isCommentEnabled()) {
            throw new BusinessException(ErrorCode.COMMENTS_DISABLED, "Comments are disabled on post: " + postId);
        }
        String content = normalize(request.content());
        Comment parent = request.parentId() == null ? null : requireReplyTarget(post, request.parentId());
        User author = userRepository.findById(userId)
                .filter(User::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED, "Inactive member: " + userId));
        Comment comment = commentRepository.save(new Comment(post, author, parent, content));
        CommentResponse response = new CommentResponse(comment.getId(), comment.getContent(), authorOf(author),
                false, comment.getCreatedAt(), comment.getUpdatedAt(), List.of());
        commentRepository.changeCommentCount(post.getId(), 1);
        events.publishEvent(new CommentCreatedEvent(comment.getId(), post.getId(), post.getTitle(),
                post.getBlog().getId(), post.getBlog().getUser().getId(), author.getId()));
        return response;
    }

    /** 작성자만 고친다. 응답의 {@code replies}는 비어 있다(목록을 다시 읽어 보여준다). */
    @Transactional
    public CommentResponse update(long userId, Long commentId, UpdateCommentRequest request) {
        Comment comment = requireVisibleComment(commentId, userId);
        if (!comment.isWrittenBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Not the author of comment: " + commentId);
        }
        comment.edit(normalize(request.content()));
        commentRepository.flush();
        User author = comment.getUser();
        return new CommentResponse(comment.getId(), comment.getContent(), authorOf(author), false,
                comment.getCreatedAt(), comment.getUpdatedAt(), List.of());
    }

    /** 작성자 또는 글 주인이 지운다. 댓글 허용이 꺼져 있어도 지울 수 있다. */
    @Transactional
    public void delete(long userId, Long commentId) {
        Comment comment = requireVisibleComment(commentId, userId);
        Post post = comment.getPost();
        if (!comment.isWrittenBy(userId) && !post.isOwnedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "Cannot delete comment: " + commentId);
        }
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
        commentRepository.changeCommentCount(post.getId(), -1);
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

    private Post requireVisiblePost(Long postId, Long viewerId) {
        return postRepository.findWithBlogAndOwner(postId)
                .filter(p -> PostExposure.isDetailVisibleTo(p, viewerId))
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND, "Post not found: " + postId));
    }

    /** 고치거나 지울 댓글: 있고, 삭제 자리가 아니고, 그 글을 이 사람이 볼 수 있어야 한다(아니면 404). */
    private Comment requireVisibleComment(Long commentId, long userId) {
        return commentRepository.findWithPostAndOwner(commentId)
                .filter(c -> !c.isDeleted())
                .filter(c -> PostExposure.isDetailVisibleTo(c.getPost(), userId))
                .orElseThrow(() -> commentNotFound(commentId));
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

    /** 작성순 행 → 1단계 트리. 답글이 하나도 남지 않은 삭제 자리는 뺀다. */
    static List<CommentResponse> toTree(List<CommentRow> rows) {
        Map<Long, List<CommentResponse>> replies = new LinkedHashMap<>();
        for (CommentRow row : rows) {
            if (row.parentId() != null && row.status() == CommentStatus.ACTIVE) {
                replies.computeIfAbsent(row.parentId(), id -> new ArrayList<>()).add(toResponse(row, List.of()));
            }
        }
        List<CommentResponse> tree = new ArrayList<>();
        for (CommentRow row : rows) {
            if (row.parentId() != null) {
                continue;
            }
            List<CommentResponse> children = List.copyOf(replies.getOrDefault(row.id(), List.of()));
            if (row.status() == CommentStatus.ACTIVE || !children.isEmpty()) {
                tree.add(toResponse(row, children));
            }
        }
        return tree;
    }

    private static CommentResponse toResponse(CommentRow row, List<CommentResponse> replies) {
        if (row.status() != CommentStatus.ACTIVE) {
            return new CommentResponse(row.id(), null, null, true, row.createdAt(), row.updatedAt(), replies);
        }
        AuthorResponse author = row.userId() == null ? null : AuthorResponse.member(row.userId(), row.nickname(),
                Media.urlOf(row.profileMediaKey()));
        return new CommentResponse(row.id(), row.content(), author, false, row.createdAt(), row.updatedAt(),
                replies);
    }
}
