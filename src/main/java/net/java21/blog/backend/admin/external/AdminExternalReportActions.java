package net.java21.blog.backend.admin.external;

import net.java21.blog.backend.admin.report.ExternalReportActions;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.external.domain.ExternalBlog;
import net.java21.blog.backend.external.domain.ExternalBlogStatus;
import net.java21.blog.backend.external.repository.ExternalBlogRepository;
import org.springframework.stereotype.Component;

/** 005 신고 처리의 007 조치(research E17): 외부 글 내림, 외부 블로그 차단(이미 차단이면 그대로). */
@Component
public class AdminExternalReportActions implements ExternalReportActions {

    private final AdminExternalPostService postService;
    private final AdminExternalBlogService blogService;
    private final ExternalBlogRepository blogRepository;

    public AdminExternalReportActions(AdminExternalPostService postService, AdminExternalBlogService blogService,
            ExternalBlogRepository blogRepository) {
        this.postService = postService;
        this.blogService = blogService;
        this.blogRepository = blogRepository;
    }

    @Override
    public void removeFromPortal(long adminId, long externalPostId, String reason, String requestIp) {
        postService.removeForReport(adminId, externalPostId, reason, requestIp);
    }

    @Override
    public void blockExternalBlog(long adminId, long externalBlogId, String reason, String requestIp) {
        ExternalBlog blog = blogRepository.findById(externalBlogId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONTENT_NOT_FOUND,
                        "Content not found: EXTERNAL_BLOG " + externalBlogId));
        if (blog.getStatus() == ExternalBlogStatus.BLOCKED) {
            return;
        }
        blogService.block(adminId, externalBlogId, reason, requestIp);
    }
}
