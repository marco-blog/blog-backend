package net.java21.blog.backend.report.service;

import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.post.repository.PostRepository;
import net.java21.blog.backend.report.domain.ReportTargetType;
import org.springframework.stereotype.Component;

/**
 * 글 신고·숨김(005 research M1·M4). 신고자는 글 상세를 볼 수 있어야 한다({@link PostExposure#isDetailVisibleTo}, 남의 비공개·임시·삭제·숨김·
 * 정지 회원 글은 404). 숨김은 {@code status_before_hidden}에 직전 상태를 남긴다.
 */
@Component
public class PostTargetHandler implements ReportTargetHandler {

    private final PostRepository postRepository;

    public PostTargetHandler(PostRepository postRepository) {
        this.postRepository = postRepository;
    }

    @Override
    public ReportTargetType type() {
        return ReportTargetType.POST;
    }

    @Override
    public ReportTarget resolveForReporter(long targetId, long reporterId) {
        Post post = postRepository.findWithBlogAndOwner(targetId)
                .filter(p -> !p.isHidden() && PostExposure.isDetailVisibleTo(p, reporterId))
                .orElseThrow(() -> ReportTargetErrors.notFound(type(), targetId));
        if (post.isOwnedBy(reporterId)) {
            throw ReportTargetErrors.own(type(), targetId);
        }
        return target(post);
    }

    @Override
    public ReportTarget resolveForAdmin(long targetId) {
        return target(require(targetId));
    }

    @Override
    public HideChange hide(long targetId) {
        Post post = require(targetId);
        String before = post.getStatus().name();
        boolean changed = post.hide();
        return new HideChange(before, post.getStatus().name(), changed);
    }

    @Override
    public HideChange unhide(long targetId) {
        Post post = require(targetId);
        String before = post.getStatus().name();
        boolean changed = post.unhide();
        return new HideChange(before, post.getStatus().name(), changed);
    }

    private Post require(long targetId) {
        return postRepository.findWithBlogAndOwner(targetId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> ReportTargetErrors.contentNotFound(type(), targetId));
    }

    private ReportTarget target(Post post) {
        return new ReportTarget(type(), post.getId(), post.getBlog().getUser(), post.getBlog(), post.getId(),
                post.getBlog().getHandle());
    }
}
