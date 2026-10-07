package net.java21.blog.backend.report.service;

import net.java21.blog.backend.comment.domain.Comment;
import net.java21.blog.backend.comment.repository.CommentRepository;
import net.java21.blog.backend.comment.service.CommentVisibility;
import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.report.domain.ReportTargetType;
import org.springframework.stereotype.Component;

/**
 * 댓글 신고·숨김(005 research M1·M4). 신고자는 댓글이 달린 글 상세를 볼 수 있고 004 {@link CommentVisibility#canRead}로 내용을 볼 수
 * 있어야 한다. 숨김은 ACTIVE ↔ HIDDEN이며 글의 {@code comment_count}를 원자적 UPDATE로 1 줄이고(해제는 1 늘림) 표시되는 댓글 수를 맞춘다.
 */
@Component
public class CommentTargetHandler implements ReportTargetHandler {

    private final CommentRepository commentRepository;

    public CommentTargetHandler(CommentRepository commentRepository) {
        this.commentRepository = commentRepository;
    }

    @Override
    public ReportTargetType type() {
        return ReportTargetType.COMMENT;
    }

    @Override
    public ReportTarget resolveForReporter(long targetId, long reporterId) {
        Comment comment = commentRepository.findWithPostAndOwner(targetId)
                .filter(Comment::isActive)
                .filter(c -> !c.getPost().isHidden() && PostExposure.isDetailVisibleTo(c.getPost(), reporterId))
                .filter(c -> readable(c, reporterId))
                .orElseThrow(() -> ReportTargetErrors.notFound(type(), targetId));
        if (comment.isWrittenBy(reporterId)) {
            throw ReportTargetErrors.own(type(), targetId);
        }
        return target(comment);
    }

    @Override
    public ReportTarget resolveForAdmin(long targetId) {
        return target(require(targetId));
    }

    @Override
    public HideChange hide(long targetId) {
        Comment comment = require(targetId);
        String before = comment.getStatus().name();
        boolean changed = comment.hide();
        if (changed) {
            commentRepository.changeCommentCount(comment.getPost().getId(), -1);
        }
        return new HideChange(before, comment.getStatus().name(), changed);
    }

    @Override
    public HideChange unhide(long targetId) {
        Comment comment = require(targetId);
        String before = comment.getStatus().name();
        boolean changed = comment.unhide();
        if (changed) {
            commentRepository.changeCommentCount(comment.getPost().getId(), 1);
        }
        return new HideChange(before, comment.getStatus().name(), changed);
    }

    private static boolean readable(Comment comment, long reporterId) {
        Comment parent = comment.getParent();
        boolean secret = CommentVisibility.isSecret(comment.isSecret(), parent == null ? null : parent.isSecret());
        Long authorId = comment.getUser() == null ? null : comment.getUser().getId();
        Long parentAuthorId = parent == null || parent.getUser() == null ? null : parent.getUser().getId();
        return CommentVisibility.canRead(secret, authorId, parentAuthorId, reporterId,
                comment.getPost().getBlog().getUser().getId());
    }

    private Comment require(long targetId) {
        return commentRepository.findWithPostAndOwner(targetId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> ReportTargetErrors.contentNotFound(type(), targetId));
    }

    private ReportTarget target(Comment comment) {
        Post post = comment.getPost();
        return new ReportTarget(type(), comment.getId(), comment.getUser(), post.getBlog(), post.getId(),
                post.getBlog().getHandle());
    }
}
