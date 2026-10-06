package net.java21.blog.backend.manage.service;

import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.manage.dto.DashboardResponse;
import net.java21.blog.backend.manage.repository.ManagePostQueryRepository;
import net.java21.blog.backend.manage.repository.ManagePostRow;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 관리 대시보드(T158, 006 FR-100의 001 범위): 임시저장 글 수와 최근 글 5편. 댓글 수치(최근 7일 새 댓글 수, 최근 댓글 5건)는
 * US3가, 방문자 수·방명록은 004가 채운다. 주인만 볼 수 있다.
 */
@Service
public class ManageDashboardService {

    /** 최근 글·최근 댓글 수 */
    static final int RECENT_SIZE = 5;

    private final BlogAccess blogAccess;
    private final ManagePostQueryRepository repository;
    private final TagQueryRepository tagQueryRepository;
    private final JobsProperties jobsProperties;

    public ManageDashboardService(BlogAccess blogAccess, ManagePostQueryRepository repository,
            TagQueryRepository tagQueryRepository, JobsProperties jobsProperties) {
        this.blogAccess = blogAccess;
        this.repository = repository;
        this.tagQueryRepository = tagQueryRepository;
        this.jobsProperties = jobsProperties;
    }

    /** 쿼리 4회(블로그, 임시저장 수, 최근 글, 태그 일괄 조회). */
    @Transactional(readOnly = true)
    public DashboardResponse dashboard(long userId, String handle) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        long draftCount = repository.countDrafts(blog.getId());
        List<ManagePostRow> rows = repository.findRecentPosts(blog.getId(), RECENT_SIZE);
        Map<Long, List<String>> tags = tagQueryRepository.findTagNames(rows.stream().map(ManagePostRow::id).toList());
        var recentPosts = rows.stream()
                .map(row -> row.toResponse(jobsProperties.trashRetention(), tags.get(row.id())))
                .toList();
        return new DashboardResponse(draftCount, recentPosts, 0, List.of());
    }
}
