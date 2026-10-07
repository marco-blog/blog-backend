package net.java21.blog.backend.report.service;

import net.java21.blog.backend.post.domain.Post;
import net.java21.blog.backend.post.repository.PostExposure;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.trackback.domain.Trackback;
import net.java21.blog.backend.trackback.domain.TrackbackStatus;
import net.java21.blog.backend.trackback.repository.TrackbackRepository;
import org.springframework.stereotype.Component;

/**
 * 트랙백 신고·숨김(005 research M2·M4). 신고자는 ACTIVE 트랙백이 붙은 글의 상세를 볼 수 있어야 하고, 서비스 안 글이 보낸 트랙백은 그 글이
 * 본문 노출 가능이어야 한다(목록 규칙과 같다). 받은 글의 주인은 신고 대신 삭제 기능을 쓴다(422). 대상 작성 회원·블로그는 서비스 안 출처면
 * 출처 글의 작성자·블로그, 밖이면 둘 다 NULL(받은 블로그를 감점하지 않음, data-model).
 */
@Component
public class TrackbackTargetHandler implements ReportTargetHandler {

    private final TrackbackRepository trackbackRepository;

    public TrackbackTargetHandler(TrackbackRepository trackbackRepository) {
        this.trackbackRepository = trackbackRepository;
    }

    @Override
    public ReportTargetType type() {
        return ReportTargetType.TRACKBACK;
    }

    @Override
    public ReportTarget resolveForReporter(long targetId, long reporterId) {
        Trackback trackback = trackbackRepository.findWithPostAndOwner(targetId)
                .filter(Trackback::isActive)
                .filter(t -> !t.getPost().isHidden() && PostExposure.isDetailVisibleTo(t.getPost(), reporterId))
                .filter(t -> t.getSourcePost() == null || PostExposure.isBodyVisible(t.getSourcePost()))
                .orElseThrow(() -> ReportTargetErrors.notFound(type(), targetId));
        if (trackback.getPost().isOwnedBy(reporterId)) {
            throw ReportTargetErrors.own(type(), targetId);
        }
        return target(trackback);
    }

    @Override
    public ReportTarget resolveForAdmin(long targetId) {
        return target(require(targetId));
    }

    @Override
    public HideChange hide(long targetId) {
        Trackback trackback = require(targetId);
        String before = trackback.getStatus().name();
        boolean changed = trackback.hide();
        return new HideChange(before, trackback.getStatus().name(), changed);
    }

    @Override
    public HideChange unhide(long targetId) {
        Trackback trackback = require(targetId);
        String before = trackback.getStatus().name();
        boolean changed = trackback.unhide();
        return new HideChange(before, trackback.getStatus().name(), changed);
    }

    private Trackback require(long targetId) {
        return trackbackRepository.findWithPostAndOwner(targetId)
                .filter(t -> t.getStatus() != TrackbackStatus.DELETED)
                .orElseThrow(() -> ReportTargetErrors.contentNotFound(type(), targetId));
    }

    private ReportTarget target(Trackback trackback) {
        Post source = trackback.getSourcePost();
        Post post = trackback.getPost();
        return new ReportTarget(type(), trackback.getId(), source == null ? null : source.getBlog().getUser(),
                source == null ? null : source.getBlog(), post.getId(), post.getBlog().getHandle());
    }
}
