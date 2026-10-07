package net.java21.blog.backend.manage.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import net.java21.blog.backend.blog.domain.Blog;
import net.java21.blog.backend.blog.service.BlogAccess;
import net.java21.blog.backend.category.domain.Category;
import net.java21.blog.backend.category.service.CategoryAccess;
import net.java21.blog.backend.common.api.FieldError;
import net.java21.blog.backend.common.error.BusinessException;
import net.java21.blog.backend.common.error.ErrorCode;
import net.java21.blog.backend.common.job.JobsProperties;
import net.java21.blog.backend.manage.dto.BulkAction;
import net.java21.blog.backend.manage.dto.BulkPostRequest;
import net.java21.blog.backend.manage.dto.BulkPostResponse;
import net.java21.blog.backend.manage.dto.ManagePostFilter;
import net.java21.blog.backend.manage.repository.ManagePostQueryRepository;
import net.java21.blog.backend.manage.repository.ManagePostRow;
import net.java21.blog.backend.post.domain.PostVisibility;
import net.java21.blog.backend.post.dto.PostSummaryResponse;
import net.java21.blog.backend.tag.repository.TagQueryRepository;
import net.java21.blog.backend.trackback.service.TrackbackSendService;
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
    private final CategoryAccess categoryAccess;
    private final TagQueryRepository tagQueryRepository;
    private final JobsProperties jobsProperties;
    private final TrackbackSendService trackbacks;
    private final Clock clock;

    public ManagePostService(BlogAccess blogAccess, ManagePostQueryRepository repository,
            CategoryAccess categoryAccess, TagQueryRepository tagQueryRepository, JobsProperties jobsProperties,
            TrackbackSendService trackbacks, Clock clock) {
        this.blogAccess = blogAccess;
        this.repository = repository;
        this.categoryAccess = categoryAccess;
        this.tagQueryRepository = tagQueryRepository;
        this.jobsProperties = jobsProperties;
        this.trackbacks = trackbacks;
        this.clock = clock;
    }

    /**
     * 관리 글 목록. 쿼리 4회(블로그, 목록, 전체 수, 태그 일괄 조회). 휴지통 글은 보관 기간 안의 것만, {@code purgeAt}과 함께.
     */
    @Transactional(readOnly = true)
    public Page<PostSummaryResponse> posts(long userId, String handle, ManagePostFilter filter, Pageable pageable) {
        Blog blog = blogAccess.requireOwnedActiveBlog(handle, userId);
        Instant trashCutoff = clock.instant().minus(jobsProperties.trashRetention());
        Page<ManagePostRow> rows = repository.findPosts(blog.getId(), filter, trashCutoff, pageable);
        Map<Long, List<String>> tags = tagQueryRepository.findTagNames(
                rows.getContent().stream().map(ManagePostRow::id).toList());
        return rows.map(row -> row.toResponse(jobsProperties.trashRetention(), tags.get(row.id())));
    }

    /**
     * 일괄 공개 범위 변경·카테고리 이동·휴지통 이동. 같은 글 ID는 한 번만 센다. 쿼리 3회(블로그, 소유 확인, UPDATE),
     * 카테고리 이동은 카테고리 확인 1회와 작성 중 사본 UPDATE 1회가 더해진다. 다른 블로그의 카테고리는 404 {@code CATEGORY_NOT_FOUND}.
     */
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
                if (request.visibility() == PostVisibility.PROTECTED) {
                    // 004 결정 표 26번: 보호 글은 비밀번호가 필요하므로 발행 설정에서만 지정한다.
                    throw invalid("visibility", "INVALID", Map.of("allowed", List.of("PUBLIC", "PRIVATE")));
                }
                requireOwned(blog, postIds);
                long hidden = repository.countHidden(blog.getId(), postIds);
                yield new BulkPostResponse(
                        repository.changeVisibility(blog.getId(), postIds, request.visibility(), clock.instant()),
                        hidden);
            }
            case MOVE_CATEGORY -> {
                Category target = request.categoryId() == null ? null
                        : categoryAccess.requireInBlog(blog.getId(), request.categoryId());
                requireOwned(blog, postIds);
                yield new BulkPostResponse(repository.moveCategory(blog.getId(), postIds, target, clock.instant()));
            }
            case DELETE -> {
                requireOwned(blog, postIds);
                long moved = repository.moveToTrash(blog.getId(), postIds, clock.instant());
                // 005 FR-052: 휴지통에 보낸 글의 보내지 않은 트랙백 요청(PENDING)은 지운다.
                trackbacks.discardPending(postIds);
                yield new BulkPostResponse(moved);
            }
            case NOTICE, UNNOTICE -> {
                requireOwned(blog, postIds);
                long hidden = repository.countHidden(blog.getId(), postIds);
                yield new BulkPostResponse(repository.changeNotice(blog.getId(), postIds,
                        request.action() == BulkAction.NOTICE), hidden);
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
