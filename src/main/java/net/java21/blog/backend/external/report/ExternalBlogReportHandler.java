package net.java21.blog.backend.external.report;

import java.util.EnumSet;
import java.util.Set;

import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.domain.ExternalPostStatus;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import net.java21.blog.backend.external.repository.ExternalPostRepository;
import net.java21.blog.backend.report.domain.ReportTargetType;
import net.java21.blog.backend.report.service.HideChange;
import net.java21.blog.backend.report.service.ReportTarget;
import net.java21.blog.backend.report.service.ReportTargetHandler;
import org.springframework.stereotype.Component;

/**
 * 외부 블로그 신고({@code EXTERNAL_BLOG}, 007 research E17). 회원 신고는 수집 중이거나 글이 보이는 등록만: ACTIVE·PAUSED·STOPPED, 또는
 * 남긴 ACTIVE 글이 있는 RELEASED(아니면 404 {@code EXTERNAL_BLOG_NOT_FOUND}). 관리 회원은 신고하지 않는다(422). 조치는
 * {@code BLOCK_EXTERNAL_BLOG}(차단, 사유는 신고 처리 메모) — 숨김·해제는 지원하지 않는다.
 */
@Component
public class ExternalBlogReportHandler implements ReportTargetHandler {

    static final Set<ExternalBlogStatus> REPORTABLE = EnumSet.of(ExternalBlogStatus.ACTIVE, ExternalBlogStatus.PAUSED,
            ExternalBlogStatus.STOPPED);

    private final ExternalBlogRepository blogRepository;
    private final ExternalPostRepository postRepository;

    public ExternalBlogReportHandler(ExternalBlogRepository blogRepository, ExternalPostRepository postRepository) {
        this.blogRepository = blogRepository;
        this.postRepository = postRepository;
    }

    @Override
    public ReportTargetType type() {
        return ReportTargetType.EXTERNAL_BLOG;
    }

    @Override
    public ReportTarget resolveForReporter(long targetId, long reporterId) {
        ExternalBlog blog = blogRepository.findById(targetId).filter(this::reportable)
                .orElseThrow(() -> new BusinessException(ErrorCode.EXTERNAL_BLOG_NOT_FOUND,
                        "External blog not found: " + targetId));
        if (blog.isManagedBy(reporterId)) {
            throw new BusinessException(ErrorCode.CANNOT_REPORT_OWN_CONTENT,
                    "Cannot report own external blog: " + targetId);
        }
        return target(blog);
    }

    @Override
    public ReportTarget resolveForAdmin(long targetId) {
        return target(blogRepository.findById(targetId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND,
                        "Content not found: EXTERNAL_BLOG " + targetId)));
    }

    @Override
    public HideChange hide(long targetId) {
        throw ExternalPostReportHandler.unsupported();
    }

    @Override
    public HideChange unhide(long targetId) {
        throw ExternalPostReportHandler.unsupported();
    }

    private boolean reportable(ExternalBlog blog) {
        if (REPORTABLE.contains(blog.getStatus())) {
            return true;
        }
        return blog.getStatus() == ExternalBlogStatus.RELEASED
                && postRepository.countByExternalBlogIdAndStatus(blog.getId(), ExternalPostStatus.ACTIVE) > 0;
    }

    private ReportTarget target(ExternalBlog blog) {
        return new ReportTarget(type(), blog.getId(), blog.getMember(), null, null, null);
    }
}
