package net.java21.blog.backend.manage.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.manage.dto.BulkPostRequest;
import net.java21.blog.backend.manage.dto.BulkPostResponse;
import net.java21.blog.backend.manage.dto.ManagePostFilter;
import net.java21.blog.backend.manage.repository.ManagePostQueryRepository;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 블로그 관리 글 목록과 일괄 작업(T158, 006 FR-101, FR-084). 블로그 주인만 쓰며({@link BlogAccess}: 삭제된 블로그 404, 주인 아님 403),
 * 일괄 작업은 고른 글이 하나라도 그 블로그의 글이 아니면 아무것도 바꾸지 않고 403 {@code FORBIDDEN}이다.
 */
@Service
public class ManagePostService {

    private final BlogAccess blogAccess;
    private final ManagePostQueryRepository repository;
    private final JobsProperties jobsProperties;
    private final Clock clock;

    public ManagePostService(BlogAccess blogAccess, ManagePostQueryRepository repository,
            JobsProperties jobsProperties, Clock clock) {
        this.blogAccess = blogAccess;
        this.repository = repository;
        this.jobsProperties = jobsProperties;
        this.clock = clock;
    }

    /** 관리 글 목록. 쿼리 3회(블로그, 목록, 전체 수). 휴지통 글은 보관 기간 안의 것만, {@code purgeAt}과 함께. */
    @Transactional(readOnly = true)
    public Page<PostSummaryResponse> posts(long userId, String handle, ManagePostFilter filter, Pageable pageable) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        Instant trashCutoff = clock.instant().minus(jobsProperties.trashRetention());
        return repository.findPosts(blog.getId(), filter, trashCutoff, pageable)
                .map(row -> row.toResponse(jobsProperties.trashRetention()));
    }

    /** 일괄 공개 범위 변경·휴지통 이동. 같은 글 ID는 한 번만 센다. 쿼리 3회(블로그, 소유 확인, UPDATE). */
    @Transactional
    public BulkPostResponse bulk(long userId, String handle, BulkPostRequest request) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        List<Long> postIds = request.postIds().stream().distinct().toList();
        if (postIds.size() > BulkPostRequest.MAX_POSTS) {
            throw invalid("postIds", "TOO_LONG", Map.of("max", BulkPostRequest.MAX_POSTS));
        }
        return switch (request.action()) {
            case CHANGE_VISIBILITY -> {
                if (request.visibility() == null) {
                    throw invalid("visibility", "REQUIRED", Map.of());
                }
                requireOwned(blog, postIds);
                yield new BulkPostResponse(
                        repository.changeVisibility(blog.getId(), postIds, request.visibility(), clock.instant()));
            }
            case DELETE -> {
                requireOwned(blog, postIds);
                yield new BulkPostResponse(repository.moveToTrash(blog.getId(), postIds, clock.instant()));
            }
        };
    }

    private void requireOwned(Blog blog, List<Long> postIds) {
        if (repository.countOwned(blog.getId(), postIds) != postIds.size()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "Some posts do not belong to blog: " + blog.getHandle());
        }
    }

    private static BusinessException invalid(String field, String code, Map<String, Object> params) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "Invalid bulk request: " + field,
                List.of(new FieldError(field, code, params)));
    }
}
